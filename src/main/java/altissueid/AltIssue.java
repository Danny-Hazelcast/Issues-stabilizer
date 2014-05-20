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
package altissueid;

import com.hazelcast.core.*;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.stabilizer.tests.TestContext;
import com.hazelcast.stabilizer.tests.TestRunner;
import com.hazelcast.stabilizer.tests.annotations.*;
import com.hazelcast.stabilizer.tests.utils.ThreadSpawner;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class AltIssue {

    private final static ILogger log = Logger.getLogger(AltIssue.class);
    private final static String alphabet = "abcdefghijklmnopqrstuvwxyz1234567890";

    public String basename = "map";
    public int threadCount = 10;
    public int keyLength = 1000;
    public int valueLength = 1000;
    public int keyCount = 1000;
    public int valueCount = 1000;
    public int logFrequency = 10000;
    //public int performanceUpdateFrequency = 10000;
    public int maxMaps = 60;

    public double writeProb = 0.3;
    public double writeUsingLockProb = 0.5;
    public double writeUnLockPauseProb = 0.5;
    public int minWriteUnLockPauseMillis = 500;
    public int maxWriteUnLockPauseMillis = 1000;

    public double writeUsingPutProb = 0.5;
    public double writeUsingExpireProb = 0.5;
    public int minExpireMillis = 500;
    public int maxExpireMillis = 1000;

    public boolean addMapEnteryListener = true;
    public int addMapEnteryListenerCount = 1;
    public boolean addLocalMapEntryListener = false;
    public int addLocalMapEntryListenerCount = 1;

    public boolean randomDistributionUniform = false;

    private String[] keys;
    private String[] values;
    private final AtomicLong operations = new AtomicLong();

    private TestContext testContext;
    private HazelcastInstance targetInstance;


    @Setup
    public void setup(TestContext testContext) throws Exception {

        this.testContext = testContext;
        targetInstance = testContext.getTargetInstance();

        targetInstance.getPartitionService().addMigrationListener(new MigrationCounter());
        targetInstance.getCluster().addMembershipListener(new MemberShipCounter());
        targetInstance.getLifecycleService().addLifecycleListener(new LifecycleCounter());
        targetInstance.addDistributedObjectListener(new DistributedObjectCounter());

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
    public void warmup(){
        for(int i=0; i< maxMaps; i++){
            IMap map = targetInstance.getMap(basename+i);

            if(addMapEnteryListener){
                for(int count=0; count<addMapEnteryListenerCount; count++){
                    map.addEntryListener(new EntryCounter(), true);
                }
            }

            if(addLocalMapEntryListener){
                for(int count=0; count<addLocalMapEntryListenerCount; count++){
                    map.addLocalEntryListener(new EntryCounter());
                }
            }

            int v=0;
            for (int k = 0; k < keys.length; k++) {
                map.put(keys[k], values[v]);
                v = (v + 1 == values.length ? 0: v + 1);
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
        for(int i=0; i< maxMaps; i++){
            IMap map = targetInstance.getMap(basename+"-"+i);
            map.destroy();
        }
    }

    @Performance
    public long getOperationCount() {
        return operations.get();
    }

    @Verify
    public void verify() throws Exception {
        log.info("operations = "+operations.get());
    }



    private class Worker implements Runnable {
        private final Random random = new Random();
        int mapIdx, keyIdx;

        public void run() {
            while (!testContext.isStopped()) {

                if(randomDistributionUniform){
                    mapIdx = random.nextInt(maxMaps);
                    keyIdx = random.nextInt(keys.length);
                }else{
                    mapIdx = getLinnearRandomNumber(maxMaps);
                    keyIdx = getLinnearRandomNumber(keys.length);
                }

                IMap map = targetInstance.getMap(basename+mapIdx);
                Object key = keys[random.nextInt(keys.length)];

                if (random.nextDouble() < writeProb) {

                    Object value = values[random.nextInt(values.length)];

                    boolean useLockThisTime;
                    if (random.nextDouble() < writeUsingLockProb) {
                        useLockThisTime=true;
                    }else{
                        useLockThisTime=false;
                    }

                    if(useLockThisTime){
                        map.lock(key);
                    }
                    try{
                        if (random.nextDouble() < writeUsingPutProb) {

                            if(random.nextDouble() < writeUsingExpireProb) {
                                int expire = random.nextInt(maxExpireMillis) + minExpireMillis;
                                map.put(key, value, expire, TimeUnit.MILLISECONDS);
                            }else{
                                map.put(key, value);
                            }

                        } else {

                            if(random.nextDouble() < writeUsingExpireProb) {
                                int expire = random.nextInt(maxExpireMillis) + minExpireMillis;
                                map.set(key, value, expire, TimeUnit.MILLISECONDS);
                            }else{
                                map.set(key, value);
                            }

                        }
                    }finally {
                        if(useLockThisTime){

                            if(random.nextDouble() < writeUnLockPauseProb){
                                int sleep = random.nextInt(maxWriteUnLockPauseMillis) + minWriteUnLockPauseMillis;
                                try {
                                    Thread.sleep(sleep);
                                } catch (InterruptedException e){}
                            }

                            map.unlock(key);
                        }
                    }
                } else {

                    map.get(key);
                }

                operations.incrementAndGet();
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




    public static void main(String[] args) throws Throwable {
        //HazelcastInstance hz1 = Hazelcast.newHazelcastInstance();
        //HazelcastInstance hz2 = Hazelcast.newHazelcastInstance();

        new TestRunner(new AltIssue()).run();
    }


    public class MigrationCounter implements MigrationListener {
        public final AtomicInteger startedCount = new AtomicInteger();
        public final AtomicInteger completedCount = new AtomicInteger();
        public final AtomicInteger failedCount = new AtomicInteger();

        @Override
        public void migrationStarted(MigrationEvent migrationEvent) {
            log.info(migrationEvent.toString());
            startedCount.incrementAndGet();
        }

        @Override
        public void migrationCompleted(MigrationEvent migrationEvent) {
            log.info(migrationEvent.toString());
            completedCount.incrementAndGet();
        }

        @Override
        public void migrationFailed(MigrationEvent migrationEvent) {
            log.info(migrationEvent.toString());
            failedCount.incrementAndGet();
        }
    };


    public class MemberShipCounter implements MembershipListener {
        public final AtomicInteger addCount = new AtomicInteger();
        public final AtomicInteger removeCount = new AtomicInteger();
        public final AtomicInteger updateCount = new AtomicInteger();

        @Override
        public void memberAdded(MembershipEvent membershipEvent) {
            log.info(membershipEvent.toString());
            addCount.incrementAndGet();
        }

        @Override
        public void memberRemoved(MembershipEvent membershipEvent) {
            log.info(membershipEvent.toString());
            removeCount.incrementAndGet();
        }

        @Override
        public void memberAttributeChanged(MemberAttributeEvent memberAttributeEvent) {
            log.info(memberAttributeEvent.toString());
            updateCount.incrementAndGet();
        }
    };

    public class LifecycleCounter implements LifecycleListener{
        public final AtomicInteger count = new AtomicInteger();

        @Override
        public void stateChanged(LifecycleEvent lifecycleEvent) {
            log.info(lifecycleEvent.toString());
            count.incrementAndGet();
        }
    }


    public class DistributedObjectCounter implements DistributedObjectListener{
        public final AtomicInteger addCount = new AtomicInteger();
        public final AtomicInteger removeCount = new AtomicInteger();

        @Override
        public void distributedObjectCreated(DistributedObjectEvent distributedObjectEvent) {
            log.info(distributedObjectEvent.toString());
            addCount.incrementAndGet();
        }

        @Override
        public void distributedObjectDestroyed(DistributedObjectEvent distributedObjectEvent) {
            log.info(distributedObjectEvent.toString());
            removeCount.incrementAndGet();
        }
    }

    public class EntryCounter implements EntryListener<Object, Object> {

        public final AtomicInteger addCount = new AtomicInteger();
        public final AtomicInteger removeCount = new AtomicInteger();
        public final AtomicInteger updateCount = new AtomicInteger();
        public final AtomicInteger evictCount = new AtomicInteger();

        public EntryCounter(){}

        @Override
        public void entryAdded(EntryEvent<Object, Object> objectObjectEntryEvent) {
            //log.info(objectObjectEntryEvent.toString());
            addCount.incrementAndGet();
        }

        @Override
        public void entryRemoved(EntryEvent<Object, Object> objectObjectEntryEvent) {
            //log.info(objectObjectEntryEvent.toString());
            removeCount.incrementAndGet();
        }

        @Override
        public void entryUpdated(EntryEvent<Object, Object> objectObjectEntryEvent) {
            //log.info(objectObjectEntryEvent.toString());
            updateCount.incrementAndGet();
        }

        @Override
        public void entryEvicted(EntryEvent<Object, Object> objectObjectEntryEvent) {
            //log.info(objectObjectEntryEvent.toString());
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

}
