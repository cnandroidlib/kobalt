package com.beust.kobalt.internal

import com.beust.kobalt.*
import com.beust.kobalt.api.DynamicTask
import com.beust.kobalt.api.IPlugin
import com.beust.kobalt.api.PluginTask
import com.beust.kobalt.api.Project
import com.beust.kobalt.api.annotation.IncrementalTask
import com.beust.kobalt.api.annotation.Task
import com.beust.kobalt.misc.Strings
import com.beust.kobalt.misc.benchmarkMillis
import com.beust.kobalt.misc.kobaltError
import com.beust.kobalt.misc.log
import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.Multimap
import com.google.common.collect.TreeMultimap
import java.lang.reflect.Method
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskManager @Inject constructor(val args: Args,
        val incrementalManagerFactory: IncrementalManager.IFactory) {
    private val dependsOn = TreeMultimap.create<String, String>()
    private val reverseDependsOn = TreeMultimap.create<String, String>()
    private val runBefore = TreeMultimap.create<String, String>()
    private val runAfter = TreeMultimap.create<String, String>()

    /**
     * Dependency: task2 depends on task 1.
     */
    fun dependsOn(task1: String, task2: String) = dependsOn.put(task2, task1)

    /**
     * Dependency: task2 depends on task 1.
     */
    fun reverseDependsOn(task1: String, task2: String) = reverseDependsOn.put(task1, task2)

    /**
     * Ordering: task1 runs before task 2.
     */
    fun runBefore(task1: String, task2: String) = runBefore.put(task1, task2)

    /**
     * Ordering: task2 runs after task 1.
     */
    fun runAfter(task1: String, task2: String) = runAfter.put(task2, task1)

    data class TaskInfo(val id: String) {
        constructor(project: String, task: String) : this(project + ":" + task)

        val project: String?
            get() = if (id.contains(":")) id.split(":")[0] else null
        val taskName: String
            get() = if (id.contains(":")) id.split(":")[1] else id
        fun matches(projectName: String) = project == null || project == projectName
    }

    class RunTargetResult(val exitCode: Int, val messages: List<String>)

    fun runTargets(taskNames: List<String>, projects: List<Project>) : RunTargetResult {
        var result = 0
        val failedProjects = hashSetOf<String>()
        val messages = Collections.synchronizedList(arrayListOf<String>())
        projects.forEach { project ->
            AsciiArt.logBox("Building ${project.name}")

            // Does the current project depend on any failed projects?
            val fp = project.projectExtra.dependsOn.filter {
                failedProjects.contains(it.name)
            }.map {
                it.name
            }

            if (fp.size > 0) {
                log(2, "Marking project ${project.name} as skipped")
                failedProjects.add(project.name)
                kobaltError("Not building project ${project.name} since it depends on failed "
                        + Strings.pluralize(fp.size, "project")
                        + " " + fp.joinToString(","))
            } else {
                // There can be multiple tasks by the same name (e.g. PackagingPlugin and AndroidPlugin both
                // define "install"), so use a multimap
                val tasksByNames = ArrayListMultimap.create<String, PluginTask>()
                annotationTasks.filter {
                    it.project.name == project.name
                }.forEach {
                    tasksByNames.put(it.name, it)
                }

                log(3, "Tasks:")
                tasksByNames.keys().forEach {
                    log(3, "  $it: " + tasksByNames.get(it))
                }

                val graph = createGraph(project.name, taskNames, tasksByNames,
                        dependsOn, reverseDependsOn, runBefore, runAfter,
                        { task: PluginTask -> task.name },
                        { task: PluginTask -> task.plugin.accept(project) })

                //
                // Now that we have a full graph, run it
                //
                log(2, "About to run graph:\n  ${graph.dump()}  ")

                val factory = object : IThreadWorkerFactory<PluginTask> {
                    override fun createWorkers(nodes: Collection<PluginTask>)
                        = nodes.map { TaskWorker(listOf(it), args.dryRun, messages) }
                }

                val executor = DynamicGraphExecutor(graph, factory)
                val thisResult = executor.run()
                if (thisResult != 0) {
                    log(2, "Marking project ${project.name} as failed")
                    failedProjects.add(project.name)
                }
                if (result == 0) {
                    result = thisResult
                }
            }
        }
        return RunTargetResult(result, messages)
    }

    /**
     * Create a dynamic graph representing all the tasks that need to be run.
     */
    @VisibleForTesting
    fun <T> createGraph(projectName: String, taskNames: List<String>, nodeMap: Multimap<String, T>,
            dependsOn: TreeMultimap<String, String>,
            reverseDependsOn: TreeMultimap<String, String>,
            runBefore: TreeMultimap<String, String>,
            runAfter: TreeMultimap<String, String>,
            toName: (T) -> String,
            accept: (T) -> Boolean):
            DynamicGraph<T> {

        val result = DynamicGraph<T>()
        taskNames.forEach { fullTaskName ->
            val ti = TaskInfo(fullTaskName)
            if (!nodeMap.keys().contains(ti.taskName)) {
                throw KobaltException("Unknown task: $fullTaskName")
            }

            if (ti.matches(projectName)) {
                val taskName = ti.taskName
                nodeMap[taskName].forEach { task ->
                    if (task != null && accept(task)) {
                        val toProcess = arrayListOf(task)
                        val seen = hashSetOf<String>()
                        val newToProcess = arrayListOf<T>()
                        while (toProcess.size > 0) {
                            toProcess.forEach { current ->
                                result.addNode(current)
                                seen.add(toName(current))

                                fun maybeAddEdge(taskName: String, mm: Multimap<String, String>, isDependency: Boolean,
                                        reverseEdges: Boolean = false) {
                                    mm[taskName]?.forEach {
                                        val addEdge = isDependency || (!isDependency && taskNames.contains(it))
                                        log(3, "    addEdge: $addEdge $taskName")
                                        if (addEdge) {
                                            nodeMap[it].forEach { to ->
                                                if (reverseEdges) {
                                                    log(3, "     Adding reverse edge $to -> $task")
                                                    result.addEdge(to, task)
                                                } else {
                                                    log(3, "    Adding edge $task -> $to")
                                                    result.addEdge(task, to)
                                                }
                                                if (!seen.contains(it)) newToProcess.add(to)
                                            }
                                            seen.add(it)
                                        }
                                    }
                                }
                                maybeAddEdge(taskName, dependsOn, true, false)
                                maybeAddEdge(taskName, reverseDependsOn, true, true)
                                maybeAddEdge(taskName, runBefore, false, false)
                                maybeAddEdge(taskName, runAfter, false, false)
                            }
                            toProcess.clear()
                            toProcess.addAll(newToProcess)
                            newToProcess.clear()
                        }
                    }
                }
            } else {
                log(3, "Task $fullTaskName does not match the current project $projectName, skipping it")
            }
        }
        return result
    }

    /**
     * Find the free tasks of the graph.
     */
    private fun <T> calculateFreeTasks(tasksByNames: Multimap<String, T>, runBefore: TreeMultimap<String, String>,
            reverseAfter: HashMap<String,
            String>)
            : Collection<T> {
        val freeTaskMap = hashMapOf<String, T>()
        tasksByNames.keys().forEach {
            if (! runBefore.containsKey(it) && ! reverseAfter.containsKey(it)) {
                tasksByNames[it].forEach { t ->
                    freeTaskMap.put(it, t)
                }
            }
        }

        return freeTaskMap.values
    }

    /////
    // Manage the tasks
    //

    // Both @Task and @IncrementalTask get stored as a TaskAnnotation so they can be treated uniformly.
    // They only differ in the way they are invoked (see below)
    private val taskAnnotations = arrayListOf<TaskAnnotation>()

    class TaskAnnotation(val method: Method, val plugin: IPlugin, val name: String, val description: String,
            val dependsOn: Array<String>, val reverseDependsOn: Array<String>,
            val runBefore: Array<String>, val runAfter: Array<String>,
            val callable: (Project) -> TaskResult)

    /**
     * Invoking a @Task means simply calling the method and returning its returned TaskResult.
     */
    fun toTaskAnnotation(method: Method, plugin: IPlugin, ta: Task)
            = TaskAnnotation(method, plugin, ta.name, ta.description, ta.dependsOn, ta.reverseDependsOn,
                ta.runBefore, ta.runAfter,
            { project ->
                method.invoke(plugin, project) as TaskResult
            })

    /**
     * Invoking an @IncrementalTask means invoking the method and then deciding what to do based on the content
     * of the returned IncrementalTaskInfo.
     */
    fun toTaskAnnotation(method: Method, plugin: IPlugin, ta: IncrementalTask)
            = TaskAnnotation(method, plugin, ta.name, ta.description, ta.dependsOn, ta.reverseDependsOn,
            ta.runBefore, ta.runAfter,
            incrementalManagerFactory.create().toIncrementalTaskClosure(ta.name, { project ->
                method.invoke(plugin, project) as IncrementalTaskInfo
            }))

    class PluginDynamicTask(val plugin: IPlugin, val task: DynamicTask)

    /** Tasks annotated with @Task or @IncrementalTask */
    val annotationTasks = arrayListOf<PluginTask>()

    /** Tasks provided by ITaskContributors */
    val dynamicTasks = arrayListOf<PluginDynamicTask>()

    fun addAnnotationTask(plugin: IPlugin, method: Method, annotation: Task) =
        taskAnnotations.add(toTaskAnnotation(method, plugin, annotation))

    fun addIncrementalTask(plugin: IPlugin, method: Method, annotation: IncrementalTask) =
        taskAnnotations.add(toTaskAnnotation(method, plugin, annotation))

    /**
     * Turn all the static and dynamic tasks into plug-in tasks, which are then suitable to be executed.
     */
    fun computePluginTasks(projects: List<Project>) {
        installAnnotationTasks(projects)
        installDynamicTasks(projects)
    }

    private fun installDynamicTasks(projects: List<Project>) {
        dynamicTasks.forEach { dynamicTask ->
            val task = dynamicTask.task
            projects.filter { dynamicTask.plugin.accept(it) }.forEach { project ->
                addTask(dynamicTask.plugin, project, task.name, task.description,
                        task.dependsOn, task.reverseDependsOn, task.runBefore, task.runAfter,
                        task.closure)
            }
        }
    }

    private fun installAnnotationTasks(projects: List<Project>) {
        taskAnnotations.forEach { staticTask ->
            val method = staticTask.method

            val methodName = method.declaringClass.toString() + "." + method.name
            log(3, "    Found task:${staticTask.name} method: $methodName")

            val plugin = staticTask.plugin
            projects.filter { plugin.accept(it) }.forEach { project ->
                addAnnotationTask(plugin, project, staticTask, staticTask.callable)
            }
        }
    }

    private fun addAnnotationTask(plugin: IPlugin, project: Project, annotation: TaskAnnotation,
            task: (Project) -> TaskResult) {
        addTask(plugin, project, annotation.name, annotation.description,
                annotation.dependsOn.toList(), annotation.reverseDependsOn.toList(),
                annotation.runBefore.toList(), annotation.runAfter.toList(),
                task)
    }

    fun addTask(plugin: IPlugin, project: Project, name: String, description: String = "",
            dependsOn: List<String> = listOf<String>(),
            reverseDependsOn: List<String> = listOf<String>(),
            runBefore: List<String> = listOf<String>(),
            runAfter: List<String> = listOf<String>(),
            task: (Project) -> TaskResult) {
        annotationTasks.add(
                object : BasePluginTask(plugin, name, description, project) {
                    override fun call(): TaskResult2<PluginTask> {
                        val taskResult = task(project)
                        return TaskResult2(taskResult.success, taskResult.errorMessage, this)
                    }
                })
        dependsOn.forEach { dependsOn(it, name) }
        reverseDependsOn.forEach { reverseDependsOn(it, name) }
        runBefore.forEach { runBefore(it, name) }
        runAfter.forEach { runAfter(it, name) }
    }

    //
    // Manage the tasks
    /////
}

class TaskWorker(val tasks: List<PluginTask>, val dryRun: Boolean, val messages: MutableList<String>)
        : IWorker<PluginTask> {

    override fun call() : TaskResult2<PluginTask> {
        if (tasks.size > 0) {
            tasks[0].let {
                log(1, AsciiArt.taskColor(AsciiArt.horizontalSingleLine + " ${it.project.name}:${it.name}"))
            }
        }
        var success = true
        val errorMessages = arrayListOf<String>()
        tasks.forEach {
            val name = it.project.name + ":" + it.name
            val time = benchmarkMillis {
                val tr = if (dryRun) TaskResult() else it.call()
                success = success and tr.success
                if (tr.errorMessage != null) errorMessages.add(tr.errorMessage)
            }
            messages.add("$name: $time ms")
        }
        return TaskResult2(success, errorMessages.joinToString("\n"), tasks[0])
    }

//    override val timeOut : Long = 10000

    override val priority: Int = 0
}