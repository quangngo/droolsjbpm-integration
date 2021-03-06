/*
 * Copyright 2019 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kie.server.services.taskassigning.planning;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.apache.commons.lang3.tuple.Pair;
import org.kie.server.api.model.taskassigning.TaskData;
import org.kie.server.api.model.taskassigning.TaskInputVariablesReadMode;
import org.kie.server.services.taskassigning.core.model.TaskAssigningSolution;
import org.kie.server.services.taskassigning.user.system.api.User;
import org.kie.server.services.taskassigning.user.system.api.UserSystemService;
import org.optaplanner.core.impl.solver.ProblemFactChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.kie.api.task.model.Status.InProgress;
import static org.kie.api.task.model.Status.Ready;
import static org.kie.api.task.model.Status.Reserved;
import static org.kie.api.task.model.Status.Suspended;
import static org.kie.server.services.taskassigning.planning.RunnableBase.Status.STARTED;
import static org.kie.server.services.taskassigning.planning.RunnableBase.Status.STOPPED;
import static org.kie.soup.commons.validation.PortablePreconditions.checkCondition;
import static org.kie.soup.commons.validation.PortablePreconditions.checkNotNull;

/**
 * This class manages the periodical reading (polling strategy) of current tasks from the jBPM runtime and depending
 * on the "action" INIT_SOLVER_EXECUTOR / SYNCHRONIZE_SOLUTION determines if the solver executor must be restarted with
 * a fully recovered solution or instead the tasks updated information is used for calculating the required changes
 * for the proper solution update. If any changes are calculated they are notified to the resultConsumer.
 * This class implements proper retries in case of connection issues with the target jBPM runtime, etc.
 */
public class SolutionSynchronizer extends RunnableBase {

    enum Action {
        INIT_SOLVER_EXECUTOR,
        SYNCHRONIZE_SOLUTION,
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(SolutionSynchronizer.class);

    private final SolverExecutor solverExecutor;
    private final TaskAssigningRuntimeDelegate delegate;
    private final UserSystemService userSystemService;
    private final long syncInterval;
    private final SolverHandlerContext context;
    private final Consumer<Result> resultConsumer;
    private int solverExecutorStarts = 0;
    private TaskAssigningSolution solution;
    private LocalDateTime fromLastModificationDate;
    private AtomicReference<Action> action = new AtomicReference<>(null);
    private final Semaphore startPermit = new Semaphore(0);

    public static class Result {

        private List<ProblemFactChange<TaskAssigningSolution>> changes;

        public Result(List<ProblemFactChange<TaskAssigningSolution>> changes) {
            this.changes = changes;
        }

        public List<ProblemFactChange<TaskAssigningSolution>> getChanges() {
            return changes;
        }
    }

    public SolutionSynchronizer(final SolverExecutor solverExecutor,
                                final TaskAssigningRuntimeDelegate delegate,
                                final UserSystemService userSystem,
                                final long syncInterval,
                                final SolverHandlerContext context,
                                final Consumer<Result> resultConsumer) {
        checkNotNull("solverExecutor", solverExecutor);
        checkNotNull("delegate", delegate);
        checkNotNull("userSystem", userSystem);
        checkCondition("syncInterval", syncInterval > 0);
        checkNotNull("context", context);
        checkNotNull("resultConsumer", resultConsumer);

        this.solverExecutor = solverExecutor;
        this.delegate = delegate;
        this.userSystemService = userSystem;
        this.syncInterval = syncInterval;
        this.context = context;
        this.resultConsumer = resultConsumer;
    }

    public void initSolverExecutor() {
        if (!status.compareAndSet(STOPPED, STARTED)) {
            throw new IllegalStateException("SolutionSynchronizer initSolverExecutor method can only be invoked when the status is STOPPED");
        }
        action.set(Action.INIT_SOLVER_EXECUTOR);
        startPermit.release();
    }

    public void synchronizeSolution(TaskAssigningSolution solution, LocalDateTime fromLastModificationDate) {
        if (!status.compareAndSet(STOPPED, STARTED)) {
            throw new IllegalStateException("SolutionSynchronizer synchronizeSolution method can only be invoked when the status is STOPPED");
        }
        this.solution = solution;
        this.fromLastModificationDate = fromLastModificationDate;
        action.set(Action.SYNCHRONIZE_SOLUTION);
        LOGGER.debug("Start synchronizeSolution fromLastModificationDate: {}", fromLastModificationDate);
        startPermit.release();
    }

    /**
     * Starts the synchronizing finalization, that will be produced as soon as possible.
     * It's a non thread-safe method, but only first invocation has effect.
     */
    @Override
    public void destroy() {
        super.destroy();
        startPermit.release(); //in case it's waiting for start.
    }

    @Override
    public void run() {
        LOGGER.debug("Solution Synchronizer Started");
        Action nextAction;
        while (isAlive()) {
            try {
                startPermit.acquire();
                if (isAlive()) {
                    if (action.get() == Action.INIT_SOLVER_EXECUTOR) {
                        nextAction = doInitSolverExecutor();
                        action.set(nextAction);
                    } else if (action.get() == Action.SYNCHRONIZE_SOLUTION) {
                        nextAction = doSynchronizeSolution();
                        action.set(nextAction);
                    }
                    if (action.get() != null) {
                        Thread.sleep(syncInterval);
                        startPermit.release();
                    } else if (isAlive()) {
                        status.set(STOPPED);
                    }
                }
            } catch (InterruptedException e) {
                super.destroy();
                Thread.currentThread().interrupt();
                LOGGER.error("Solution Synchronizer was interrupted.", e);
            }
        }
        super.destroy();
        LOGGER.debug("Solution Synchronizer finished");
    }

    Action doInitSolverExecutor() {
        Action nextAction = null;
        try {
            LOGGER.debug("Solution Synchronizer will recover the solution from the jBPM runtime for starting the solver.");
            if (!solverExecutor.isStopped()) {
                LOGGER.debug("Previous solver instance has not yet finished, let's wait for it to stop." +
                                     " Next attempt will be in {} milliseconds.", syncInterval);
                nextAction = Action.INIT_SOLVER_EXECUTOR;
            } else {
                final TaskAssigningSolution recoveredSolution = recoverSolution();
                if (isAlive() && !solverExecutor.isDestroyed()) {
                    if (!recoveredSolution.getTaskList().isEmpty()) {
                        solverExecutor.start(recoveredSolution);
                        LOGGER.debug("Solution was successfully recovered. Solver was started for #{} time.", ++solverExecutorStarts);
                        if (solverExecutorStarts > 1) {
                            LOGGER.debug("It looks like it was necessary to restart the solver. It might" +
                                                 " have been caused due to errors during the solution applying in the jBPM runtime");
                        }
                    } else {
                        nextAction = Action.INIT_SOLVER_EXECUTOR;
                        LOGGER.debug("It looks like there are no tasks for recovering the solution at this moment." +
                                             " Next attempt will be in {} milliseconds", syncInterval);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("An error was produced during solution recovering." +
                                 " Next attempt will be in " + syncInterval + " milliseconds", e);
            nextAction = Action.INIT_SOLVER_EXECUTOR;
        }
        return nextAction;
    }

    Action doSynchronizeSolution() {
        Action nextAction = null;
        try {
            if (solverExecutor.isStarted()) {
                LOGGER.debug("Synchronizing solution status from the jBPM runtime.");
                final Pair<List<TaskData>, LocalDateTime> result = loadTasksForUpdate(fromLastModificationDate);
                final List<TaskData> updatedTaskDataList = result.getLeft();
                LOGGER.debug("Status was read successful.");
                if (isAlive()) {
                    final List<ProblemFactChange<TaskAssigningSolution>> changes = buildChanges(solution, updatedTaskDataList);
                    context.setPreviousQueryTime(fromLastModificationDate);
                    LocalDateTime nextQueryTime = trimMillis(result.getRight());
                    LocalDateTime lastQueryTime = context.peekLastQueryTime();
                    if (!context.hasMinimalDistance(lastQueryTime, nextQueryTime)) {
                        nextQueryTime = lastQueryTime;
                    }
                    context.addNextQueryTime(nextQueryTime);
                    if (!changes.isEmpty()) {
                        LOGGER.debug("Current solution will be updated with {} changes from last synchronization", changes.size());
                        resultConsumer.accept(new Result(changes));
                    } else {
                        LOGGER.debug("There are no changes to apply from last synchronization.");
                        fromLastModificationDate = context.pollNextQueryTime();
                        nextAction = Action.SYNCHRONIZE_SOLUTION;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("An error was produced during solution status synchronization from the jBPM runtime." +
                                 " Next attempt will be in " + syncInterval + " milliseconds", e);
            nextAction = Action.SYNCHRONIZE_SOLUTION;
        }
        return nextAction;
    }

    protected List<ProblemFactChange<TaskAssigningSolution>> buildChanges(TaskAssigningSolution solution, List<TaskData> updatedTaskDataList) {
        return SolutionChangesBuilder.create()
                .withSolution(solution)
                .withTasks(updatedTaskDataList)
                .withUserSystem(userSystemService)
                .withContext(context)
                .build();
    }

    private TaskAssigningSolution recoverSolution() {
        final TaskAssigningRuntimeDelegate.FindTasksResult result = delegate.findTasks(Arrays.asList(Ready,
                                                                                                     Reserved,
                                                                                                     InProgress,
                                                                                                     Suspended),
                                                                                       null,
                                                                                       TaskInputVariablesReadMode.READ_FOR_ALL);

        final LocalDateTime nextQueryTime = trimMillis(result.getQueryTime());
        final LocalDateTime adjustedFirstQueryTime = nextQueryTime != null ? nextQueryTime.minusHours(1) : null;
        context.setPreviousQueryTime(adjustedFirstQueryTime);
        context.resetQueryTimes(adjustedFirstQueryTime);
        context.pollLastQueryTime();
        context.addNextQueryTime(nextQueryTime);
        context.clearTaskChangeTimes();
        final List<TaskData> taskDataList = result.getTasks();
        LOGGER.debug("{} tasks where loaded for solution recovery, with result.queryTime: {}", taskDataList.size(), result.getQueryTime());
        final List<User> externalUsers = userSystemService.findAllUsers();
        return buildSolution(taskDataList, externalUsers);
    }

    protected TaskAssigningSolution buildSolution(List<TaskData> taskDataList, List<User> externalUsers) {
        return SolutionBuilder.create()
                .withTasks(taskDataList)
                .withUsers(externalUsers)
                .withContext(context)
                .build();
    }

    private Pair<List<TaskData>, LocalDateTime> loadTasksForUpdate(LocalDateTime fromLastModificationDate) {
        final TaskAssigningRuntimeDelegate.FindTasksResult result = delegate.findTasks(null,
                                                                                       fromLastModificationDate,
                                                                                       TaskInputVariablesReadMode.READ_FOR_ACTIVE_TASKS_WITH_NO_PLANNING_ENTITY);
        LOGGER.debug("Total modifications found: {} since fromLastModificationDate: {}, with result.queryTime: {}",
                     result.getTasks().size(), fromLastModificationDate, result.getQueryTime());
        return Pair.of(result.getTasks(), result.getQueryTime());
    }

    private static LocalDateTime trimMillis(LocalDateTime localDateTime) {
        // trim to 0 milliseconds to avoid falling into https://issues.redhat.com/browse/JBPM-8970 (but note that
        // the trimming is still good to avoid any other potential date or timestamp DBMS dependent issue)
        return localDateTime != null ? localDateTime.withNano(0) : null;
    }
}
