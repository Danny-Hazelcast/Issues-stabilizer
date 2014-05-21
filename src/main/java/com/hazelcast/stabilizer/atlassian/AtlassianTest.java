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
import com.hazelcast.map.EntryBackupProcessor;
import com.hazelcast.map.EntryProcessor;
import com.hazelcast.stabilizer.Utils;
import com.hazelcast.stabilizer.tests.TestContext;
import com.hazelcast.stabilizer.tests.TestRunner;
import com.hazelcast.stabilizer.tests.annotations.*;
import com.hazelcast.stabilizer.tests.utils.ThreadSpawner;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class AtlassianTest {

    private final static ILogger log = Logger.getLogger(AtlassianTest.class);
    private final static String alphabet = "abcdefghijklmnopqrstuvwxyz1234567890";

    public String basename = "map";
    public int threadCount = 30;
    public int keyLength = 100;
    public int valueLength = 100;
    public int keyCount = 100000;
    public int valueCount = 100000;
    public int maxMaps = 40;

    public boolean randomDistributionUniform=false;

    //add up to 1
    public double writeProb = 0.4;
    public double getProb = 0.3;

    public double clearProb = 0.05;
    public double replaceProb = 0.1;
    public double removeProb = 0.1;
    public double exicuteOnProb = 0.05;
    //

    //add up to 1   (writeProb is splitup int sub styles)
    public double writeUsingPutProb = 0.5;
    public double writeUsingPutIfAbsent = 0.25;
    public double writeUsingPutExpireProb = 0.25;
    //

    public int minExpireMillis = 500;
    public int maxExpireMillis = 1000;

    public int migrationListenerCount = 1;
    public int migrationListenerDelayMills = 40;

    public int membershipListenerCount = 1;
    public int membershipListenerDelayMills = 30;

    public int lifecycleListenerCount = 1;
    public int lifecycleListenerDelayMills = 20;

    public int distributedObjectListenerCount = 1;
    public int distributedObjectListenerDelayMills = 10;

    public int localMapEntryListenerCount = 1;
    public int localMapEntryListenerDelayMills = 20;

    public int mapEntryListenerCount = 3;
    public int mapEntryListenerDelayMills = 50;

    public int entryProcessorDelayMills = 100;


    private String[] keys;
    private String[] values;
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
        warmup();
    }

    //@Warmup
    public void warmup() {
        log.info("===WARMUP===");

        keys = new String[keyCount];
        values = new String[valueCount];

        for (int k = 0; k < keys.length; k++) {
            keys[k] = makeString(keyLength);
        }

        for (int k = 0; k < values.length; k++) {
            values[k] = makeString(valueLength);
        }

        for (int i = 0; i < maxMaps; i++) {
            IMap map = targetInstance.getMap(basename + i);

            for (int count = 0; count < mapEntryListenerCount; count++) {
                map.addEntryListener(new EntryListenerImpl(mapEntryListenerDelayMills), true);
            }

            for (int count = 0; count < localMapEntryListenerCount; count++) {
                map.addLocalEntryListener(new EntryListenerImpl(localMapEntryListenerDelayMills));
            }

            int v = 0;
            for (int k = 0; k < keys.length; k++) {
                map.put(keys[k], values[v]);
                v = (v + 1 == values.length ? 0 : v + 1);
            }
        }
        log.info("===WARMUP===");
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
            IMap map = targetInstance.getMap(basename + i);
            map.destroy();
        }
    }

    @Performance
    public long getOperationCount() {
        return 1;
    }

    @Verify
    public void verify() throws Exception {
        log.info("verify = ");
    }


    private class Worker implements Runnable {
        private final Random random = new Random();
        int mapIdx, keyIdx;

        public void run() {
            long iteration = 0;

            while (!testContext.isStopped()) {

                if(randomDistributionUniform){
                    mapIdx = random.nextInt(maxMaps);
                    keyIdx = random.nextInt(keys.length);
                }else{
                    mapIdx = getLinnearRandomNumber(maxMaps);
                    keyIdx = getLinnearRandomNumber(keys.length);
                }


                IMap map = targetInstance.getMap(basename + mapIdx);
                Object key = keys[random.nextInt(keys.length)];

                double chance = random.nextDouble();
                if (chance < writeProb) {

                    Object value = values[random.nextInt(values.length)];

                    chance = random.nextDouble();
                    if (chance < writeUsingPutProb) {
                        map.put(key, value);
                    }
                    else if(chance < writeUsingPutIfAbsent + writeUsingPutProb ){
                        map.putIfAbsent(key, value);
                    }
                    else if ( chance <  writeUsingPutExpireProb + writeUsingPutIfAbsent + writeUsingPutProb) {
                        int expire = random.nextInt(maxExpireMillis) + minExpireMillis;
                        map.put(key, value, expire, TimeUnit.MILLISECONDS);
                    }
                    else{
                        log.info("DID NOT ADD UP to (1) "+writeUsingPutExpireProb + writeUsingPutIfAbsent + writeUsingPutProb);
                    }

                }else if(chance < getProb + writeProb){
                    map.get(key);
                }
                else if(chance < clearProb + getProb + writeProb){
                    map.clear();
                }
                else if(chance < replaceProb + clearProb + getProb + writeProb){
                    Object value = values[random.nextInt(values.length)];
                    map.replace(key, value);
                }
                else if(chance < removeProb + replaceProb + clearProb + getProb + writeProb){
                    map.remove(key);
                }
                else if(chance < exicuteOnProb + removeProb + replaceProb + clearProb + getProb + writeProb){
                    map.executeOnKey(key, new EntryProcessorImpl(entryProcessorDelayMills));
                }
                else{
                    log.info("DID NOT ADD UP");
                }
            }
        }

        public int getLinnearRandomNumber(int maxSize){
            maxSize--;
            //Get a linearly multiplied random number
            int randomMultiplier = maxSize * (maxSize + 1) / 2;
            int randomInt = random.nextInt(randomMultiplier);

            //Linearly iterate through the possible values to find the correct one
            int linearRandomNumber = 0;
            for(int i=maxSize; randomInt >= 0; i--){
                randomInt -= i;
                linearRandomNumber++;
            }

            return linearRandomNumber;
        }
    }



    public static class EntryProcessorImpl implements  EntryProcessor {

        public int entryProcessorDelayMills =0;

        public EntryProcessorImpl(int entryProcessorDelayNs){
            this.entryProcessorDelayMills = entryProcessorDelayNs;
        }

        public Object process(Map.Entry entry) {
            Utils.sleepMillis(entryProcessorDelayMills);

            return entry.getValue();
        }

        @Override
        public EntryBackupProcessor getBackupProcessor() {
            Utils.sleepMillis(entryProcessorDelayMills);
            return null;
        }
    }


    public class MigrationnListenerImpl implements MigrationListener {
        public final AtomicInteger startedCount = new AtomicInteger();
        public final AtomicInteger completedCount = new AtomicInteger();
        public final AtomicInteger failedCount = new AtomicInteger();

        @Override
        public void migrationStarted(MigrationEvent migrationEvent) {
            Utils.sleepMillis(migrationListenerDelayMills);
            startedCount.incrementAndGet();
        }

        @Override
        public void migrationCompleted(MigrationEvent migrationEvent) {
            Utils.sleepMillis(migrationListenerDelayMills);
            completedCount.incrementAndGet();
        }

        @Override
        public void migrationFailed(MigrationEvent migrationEvent) {
            Utils.sleepMillis(migrationListenerDelayMills);
            failedCount.incrementAndGet();
        }
    }


    public class MembershipListenerImpl implements MembershipListener {
        public final AtomicInteger addCount = new AtomicInteger();
        public final AtomicInteger removeCount = new AtomicInteger();
        public final AtomicInteger updateCount = new AtomicInteger();

        @Override
        public void memberAdded(MembershipEvent membershipEvent) {
            Utils.sleepMillis(membershipListenerDelayMills);
            addCount.incrementAndGet();
        }

        @Override
        public void memberRemoved(MembershipEvent membershipEvent) {
            Utils.sleepMillis(membershipListenerDelayMills);
            removeCount.incrementAndGet();
        }

        @Override
        public void memberAttributeChanged(MemberAttributeEvent memberAttributeEvent) {
            Utils.sleepMillis(membershipListenerDelayMills);
            updateCount.incrementAndGet();
        }
    }

    public class LifecycleListenerImpl implements LifecycleListener {
        public final AtomicInteger count = new AtomicInteger();

        @Override
        public void stateChanged(LifecycleEvent lifecycleEvent) {
            Utils.sleepMillis(lifecycleListenerDelayMills);
            count.incrementAndGet();
        }
    }


    public class DistributedObjectListenerImpl implements DistributedObjectListener {
        public final AtomicInteger addCount = new AtomicInteger();
        public final AtomicInteger removeCount = new AtomicInteger();

        @Override
        public void distributedObjectCreated(DistributedObjectEvent distributedObjectEvent) {
            Utils.sleepMillis(distributedObjectListenerDelayMills);
            addCount.incrementAndGet();
        }

        @Override
        public void distributedObjectDestroyed(DistributedObjectEvent distributedObjectEvent) {
            Utils.sleepMillis(distributedObjectListenerDelayMills);
            removeCount.incrementAndGet();
        }
    }

    public class EntryListenerImpl implements EntryListener<Object, Object> {

        public final AtomicInteger addCount = new AtomicInteger();
        public final AtomicInteger removeCount = new AtomicInteger();
        public final AtomicInteger updateCount = new AtomicInteger();
        public final AtomicInteger evictCount = new AtomicInteger();
        private final int delay;

        public EntryListenerImpl(int delayNs) {
            this.delay = delayNs;
        }

        @Override
        public void entryAdded(EntryEvent<Object, Object> objectObjectEntryEvent) {
            Utils.sleepMillis(delay);
            addCount.incrementAndGet();
        }

        @Override
        public void entryRemoved(EntryEvent<Object, Object> objectObjectEntryEvent) {
            Utils.sleepMillis(delay);
            removeCount.incrementAndGet();
        }

        @Override
        public void entryUpdated(EntryEvent<Object, Object> objectObjectEntryEvent) {
            Utils.sleepMillis(delay);
            updateCount.incrementAndGet();
        }

        @Override
        public void entryEvicted(EntryEvent<Object, Object> objectObjectEntryEvent) {
            Utils.sleepMillis(delay);
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
