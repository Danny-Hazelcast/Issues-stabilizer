/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hazelcast.stabilizer.atlassian;

import com.hazelcast.core.DistributedObjectEvent;
import com.hazelcast.core.DistributedObjectListener;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.EntryListener;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.core.LifecycleEvent;
import com.hazelcast.core.LifecycleListener;
import com.hazelcast.core.MemberAttributeEvent;
import com.hazelcast.core.MembershipEvent;
import com.hazelcast.core.MembershipListener;
import com.hazelcast.core.MigrationEvent;
import com.hazelcast.core.MigrationListener;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.stabilizer.Utils;
import com.hazelcast.stabilizer.tests.TestContext;
import com.hazelcast.stabilizer.tests.TestRunner;
import com.hazelcast.stabilizer.tests.annotations.Performance;
import com.hazelcast.stabilizer.tests.annotations.Run;
import com.hazelcast.stabilizer.tests.annotations.Setup;
import com.hazelcast.stabilizer.tests.annotations.Teardown;
import com.hazelcast.stabilizer.tests.annotations.Verify;
import com.hazelcast.stabilizer.tests.annotations.Warmup;
import com.hazelcast.stabilizer.tests.utils.ThreadSpawner;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class AtlassianTest {

    private final static ILogger log = Logger.getLogger(AtlassianTest.class);
    private final static String alphabet = "abcdefghijklmnopqrstuvwxyz1234567890";

    public String basename = "map";
    public int threadCount = 10;
    public int keyLength = 1000;
    public int valueLength = 1000;
    public int keyCount = 1000;
    public int valueCount = 1000;
    public int logFrequency = 10000;
    public int performanceUpdateFrequency = 10000;
    public int maxMaps = 60;

    public double writeProb = 0.3;

    public double writeUsingPutProb = 0.5;
    public double writeUsingExpireProb = 0.5;
    public int minExpireMillis = 500;
    public int maxExpireMillis = 1000;

    public int migrationListenerCount = 1;
    public long migrationListenerDelayNs = 0;

    public int membershipListenerCount = 1;
    public long membershipListenerDelayNs = 0;

    public int lifecycleListenerCount = 1;
    public long lifecycleListenerDelayNs = 0;

    public int distributedObjectListenerCount = 1;
    public long distributedObjectListenerDelayNs = 0;

    public long localMapEntryListenerCount = 1;
    public long localMapEntryListenerDelayNs = 0;

    public long mapEntryListenerCount = 1;
    public long mapEntryListenerDelayNs = 0;

    private String[] keys;
    private String[] values;
    private final AtomicLong operations = new AtomicLong();

    private TestContext testContext;
    private HazelcastInstance targetInstance;

    @Setup
    public void setup(TestContext testContext) throws Exception {
        this.testContext = testContext;
        targetInstance = testContext.getTargetInstance();

        for (int k = 0; k < migrationListenerCount; k++) {
            targetInstance.getPartitionService().addMigrationListener(new MigrationnListenerImpl());
        }

        for (int k = 0; k < membershipListenerCount; k++) {
            targetInstance.getCluster().addMembershipListener(new MembershipListenerImpl());
        }

        for (int k = 0; k < lifecycleListenerCount; k++) {
            targetInstance.getLifecycleService().addLifecycleListener(new LifecycleListenerImpl());
        }

        for (int k = 0; k < distributedObjectListenerCount; k++) {
            targetInstance.addDistributedObjectListener(new DistributedObjectListenerImpl());
        }

        keys = new String[keyCount];
        values = new String[valueCount];

        for (int k = 0; k < keys.length; k++) {
            keys[k] = makeString(keyLength);
        }

        for (int k = 0; k < values.length; k++) {
            values[k] = makeString(valueLength);
        }
    }

    @Warmup
    public void warmup() {
        for (int i = 0; i < maxMaps; i++) {
            IMap map = targetInstance.getMap(basename + i);

            for (int count = 0; count < mapEntryListenerCount; count++) {
                map.addEntryListener(new EntryListenerImpl(mapEntryListenerDelayNs), true);
            }

            for (int count = 0; count < localMapEntryListenerCount; count++) {
                map.addLocalEntryListener(new EntryListenerImpl(localMapEntryListenerDelayNs));
            }

            int v = 0;
            for (int k = 0; k < keys.length; k++) {
                map.put(keys[k], values[v]);
                v = (v + 1 == values.length ? 0 : v + 1);
            }
        }
    }

    private String makeString(int length) {
        Random random = new Random();

        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < length; k++) {
            char c = alphabet.charAt(random.nextInt(alphabet.length()));
            sb.append(c);
        }

        return sb.toString();
    }

    @Run
    public void run() {
        ThreadSpawner spawner = new ThreadSpawner();
        for (int k = 0; k < threadCount; k++) {
            spawner.spawn(new Worker());
        }
        spawner.awaitCompletion();
    }

    @Teardown
    public void globalTearDown() throws Exception {
        for (int i = 0; i < maxMaps; i++) {
            IMap map = targetInstance.getMap(basename + "-" + i);
            map.destroy();
        }
    }

    @Performance
    public long getOperationCount() {
        return operations.get();
    }

    @Verify
    public void verify() throws Exception {
        log.info("operations = " + operations.get());
    }


    private class Worker implements Runnable {
        private final Random random = new Random();
        int mapIdx, keyIdx;

        public void run() {
            while (!testContext.isStopped()) {
                mapIdx = random.nextInt(maxMaps);
                keyIdx = random.nextInt(keys.length);

                IMap map = targetInstance.getMap(basename + mapIdx);
                Object key = keys[random.nextInt(keys.length)];

                if (random.nextDouble() < writeProb) {
                    Object value = values[random.nextInt(values.length)];

                    if (random.nextDouble() < writeUsingPutProb) {
                        if (random.nextDouble() < writeUsingExpireProb) {
                            int expire = random.nextInt(maxExpireMillis) + minExpireMillis;
                            map.put(key, value, expire, TimeUnit.MILLISECONDS);
                        } else {
                            map.put(key, value);
                        }

                    } else {
                        if (random.nextDouble() < writeUsingExpireProb) {
                            int expire = random.nextInt(maxExpireMillis) + minExpireMillis;
                            map.set(key, value, expire, TimeUnit.MILLISECONDS);
                        } else {
                            map.set(key, value);
                        }

                    }
                } else {
                    map.get(key);
                }

                operations.incrementAndGet();
            }
        }

    }


    public class MigrationnListenerImpl implements MigrationListener {
        public final AtomicInteger startedCount = new AtomicInteger();
        public final AtomicInteger completedCount = new AtomicInteger();
        public final AtomicInteger failedCount = new AtomicInteger();

        @Override
        public void migrationStarted(MigrationEvent migrationEvent) {
            Utils.sleepNanos(migrationListenerDelayNs);
            startedCount.incrementAndGet();
        }

        @Override
        public void migrationCompleted(MigrationEvent migrationEvent) {
            Utils.sleepNanos(migrationListenerDelayNs);
            completedCount.incrementAndGet();
        }

        @Override
        public void migrationFailed(MigrationEvent migrationEvent) {
            Utils.sleepNanos(migrationListenerDelayNs);
            failedCount.incrementAndGet();
        }
    }


    public class MembershipListenerImpl implements MembershipListener {
        public final AtomicInteger addCount = new AtomicInteger();
        public final AtomicInteger removeCount = new AtomicInteger();
        public final AtomicInteger updateCount = new AtomicInteger();

        @Override
        public void memberAdded(MembershipEvent membershipEvent) {
            Utils.sleepNanos(membershipListenerDelayNs);
            addCount.incrementAndGet();
        }

        @Override
        public void memberRemoved(MembershipEvent membershipEvent) {
            Utils.sleepNanos(membershipListenerDelayNs);
            removeCount.incrementAndGet();
        }

        @Override
        public void memberAttributeChanged(MemberAttributeEvent memberAttributeEvent) {
            Utils.sleepNanos(membershipListenerDelayNs);
            updateCount.incrementAndGet();
        }
    }

    public class LifecycleListenerImpl implements LifecycleListener {
        public final AtomicInteger count = new AtomicInteger();

        @Override
        public void stateChanged(LifecycleEvent lifecycleEvent) {
            Utils.sleepNanos(lifecycleListenerDelayNs);
            count.incrementAndGet();
        }
    }


    public class DistributedObjectListenerImpl implements DistributedObjectListener {
        public final AtomicInteger addCount = new AtomicInteger();
        public final AtomicInteger removeCount = new AtomicInteger();

        @Override
        public void distributedObjectCreated(DistributedObjectEvent distributedObjectEvent) {
            Utils.sleepNanos(distributedObjectListenerDelayNs);
            addCount.incrementAndGet();
        }

        @Override
        public void distributedObjectDestroyed(DistributedObjectEvent distributedObjectEvent) {
            Utils.sleepNanos(distributedObjectListenerDelayNs);
            removeCount.incrementAndGet();
        }
    }

    public class EntryListenerImpl implements EntryListener<Object, Object> {

        public final AtomicInteger addCount = new AtomicInteger();
        public final AtomicInteger removeCount = new AtomicInteger();
        public final AtomicInteger updateCount = new AtomicInteger();
        public final AtomicInteger evictCount = new AtomicInteger();
        private final long delayNs;

        public EntryListenerImpl(long delayNs) {
            this.delayNs = delayNs;
        }

        @Override
        public void entryAdded(EntryEvent<Object, Object> objectObjectEntryEvent) {
            Utils.sleepNanos(delayNs);
            addCount.incrementAndGet();
        }

        @Override
        public void entryRemoved(EntryEvent<Object, Object> objectObjectEntryEvent) {
            Utils.sleepNanos(delayNs);
            removeCount.incrementAndGet();
        }

        @Override
        public void entryUpdated(EntryEvent<Object, Object> objectObjectEntryEvent) {
            Utils.sleepNanos(delayNs);
            updateCount.incrementAndGet();
        }

        @Override
        public void entryEvicted(EntryEvent<Object, Object> objectObjectEntryEvent) {
            Utils.sleepNanos(delayNs);
            evictCount.incrementAndGet();
        }

        @Override
        public String toString() {
            return "EntryCounter{" +
                    "addCount=" + addCount +
                    ", removeCount=" + removeCount +
                    ", updateCount=" + updateCount +
                    ", evictCount=" + evictCount +
                    '}';
        }
    }

    public static void main(String[] args) throws Throwable {
        new TestRunner(new AtlassianTest()).run();
    }
}
