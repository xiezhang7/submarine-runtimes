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

package org.kie.kogito.services.uow;

import org.kie.kogito.event.EventBatch;
import org.kie.kogito.event.EventManager;
import org.kie.kogito.process.MutableProcessInstances;
import org.kie.kogito.uow.UnitOfWork;
import org.kie.kogito.uow.WorkUnit;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;

/**
 * Simple unit of work that collects work elements
 * throughout the life of the unit and invokes all of them at the end
 * when end method is invoked. It does not invoke the work
 * when abort is invoked, only clears the collected items.
 */
public class CollectingUnitOfWork implements UnitOfWork {

    private Set<WorkUnit<?>> collectedWork;
    private boolean done;

    private final EventManager eventManager;


    public CollectingUnitOfWork(EventManager eventManager) {
        this.eventManager = eventManager;
    }

    @Override
    public void start() {
        checkDone();
        if (collectedWork == null) {
            collectedWork = new LinkedHashSet<>();
        }
    }

    @Override
    public void end() {
        checkStarted();
        EventBatch batch = eventManager.newBatch();

        Optional<? extends MutableProcessInstances<?>> instances = sorted().stream()
                .filter(work -> work instanceof ProcessInstanceWorkUnit)
                .map(work -> ((ProcessInstanceWorkUnit<?>) work).data().process().instances())
                .filter(ins -> ins instanceof MutableProcessInstances)
                .map(ins -> (MutableProcessInstances<?>) ins).findAny();

        if (instances.isPresent()) {
            instances.ifPresent(i -> {
                try (Closeable closeable = i.startTransaction()) {
                    for (WorkUnit<?> work : sorted()) {
                        batch.append(work.data());
                        work.perform(closeable);
                    }
                    eventManager.publish(batch, closeable);
                    i.commitTransaction(closeable);
                    done();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } else {
            for (WorkUnit<?> work : sorted()) {
                batch.append(work.data());
                work.perform();
            }
            eventManager.publish(batch);
            done();
        }
    }

    @Override
    public void abort() {
        checkStarted();
        for (WorkUnit<?> work : sorted()) {
            work.abort();
        }
        done();
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void intercept(WorkUnit work) {
        checkStarted();
        if (work == null) {
            throw new NullPointerException("Work must be non null");
        }
        collectedWork.remove(work);
        collectedWork.add(work);
    }

    protected Collection<WorkUnit<?>> sorted() {
        List<WorkUnit<?>> sortedCollectedWork = new ArrayList<>(collectedWork);
        sortedCollectedWork.sort((u1, u2) -> u1.priority().compareTo(u2.priority()));

        return sortedCollectedWork;
    }


    protected void checkDone() {
        if (done) {
            throw new IllegalStateException("Unit of work is already done (ended or aborted)");
        }
    }

    protected void checkStarted() {
        if (collectedWork == null) {
            throw new IllegalStateException("Unit of work is not started");
        }
    }

    protected void done() {
        done = true;
        collectedWork = null;
    }
}
