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

package com.hazelcast.instance;

import com.hazelcast.collection.list.ListService;
import com.hazelcast.collection.set.SetService;
import com.hazelcast.concurrent.atomicreference.AtomicReferenceService;
import com.hazelcast.concurrent.lock.proxy.LockProxy;
import com.hazelcast.multimap.MultiMapService;
import com.hazelcast.concurrent.atomiclong.AtomicLongService;
import com.hazelcast.concurrent.countdownlatch.CountDownLatchService;
import com.hazelcast.concurrent.idgen.IdGeneratorService;
import com.hazelcast.concurrent.lock.LockService;
import com.hazelcast.concurrent.semaphore.SemaphoreService;
import com.hazelcast.config.Config;
import com.hazelcast.core.*;
import com.hazelcast.executor.DistributedExecutorService;
import com.hazelcast.jmx.ManagementService;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.LoggingService;
import com.hazelcast.management.ThreadMonitoringService;
import com.hazelcast.map.MapService;
import com.hazelcast.nio.serialization.SerializationService;
import com.hazelcast.queue.QueueService;
import com.hazelcast.replicatedmap.ReplicatedMapService;
import com.hazelcast.spi.ProxyService;
import com.hazelcast.spi.annotation.PrivateApi;
import com.hazelcast.topic.TopicService;
import com.hazelcast.transaction.TransactionContext;
import com.hazelcast.transaction.TransactionException;
import com.hazelcast.transaction.TransactionOptions;
import com.hazelcast.transaction.TransactionalTask;
import com.hazelcast.util.HealthMonitor;
import com.hazelcast.util.HealthMonitorLevel;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.hazelcast.core.LifecycleEvent.LifecycleState.STARTING;

/**
 * @author mdogan 7/31/12
 */

@SuppressWarnings("unchecked")
@PrivateApi
public final class HazelcastInstanceImpl implements HazelcastInstance {

    public final Node node;

    final ILogger logger;

    final String name;

    final ManagementService managementService;

    final LifecycleServiceImpl lifecycleService;

    final ManagedContext managedContext;

    final ThreadMonitoringService threadMonitoringService;

    final ThreadGroup threadGroup;

    final ConcurrentMap<String, Object> userContext = new ConcurrentHashMap<String, Object>();

    HazelcastInstanceImpl(String name, Config config, NodeContext nodeContext) throws Exception {
        this.name = name;
        this.threadGroup = new ThreadGroup(name);
        threadMonitoringService = new ThreadMonitoringService(threadGroup);
        lifecycleService = new LifecycleServiceImpl(this);
        ManagedContext configuredManagedContext = config.getManagedContext();
        managedContext = new HazelcastManagedContext(this, configuredManagedContext);

        //we are going to copy the user-context map of the Config so that each HazelcastInstance will get its own
        //user-context map instance instead of having a shared map instance. So changes made to the user-context map
        //in one HazelcastInstance will not reflect on other the user-context of other HazelcastInstances.
        userContext.putAll(config.getUserContext());
        node = new Node(this, config, nodeContext);
        logger = node.getLogger(getClass().getName());
        lifecycleService.fireLifecycleEvent(STARTING);
        node.start();
        if (!node.isActive()) {
            node.connectionManager.shutdown();
            throw new IllegalStateException("Node failed to start!");
        }
        managementService = new ManagementService(this);

        if (configuredManagedContext != null) {
            if (configuredManagedContext instanceof HazelcastInstanceAware) {
                ((HazelcastInstanceAware) configuredManagedContext).setHazelcastInstance(this);
            }
        }

        HealthMonitorLevel healthLevel = HealthMonitorLevel.valueOf(node.getGroupProperties().HEALTH_MONITORING_LEVEL.getString());
        if(healthLevel!=HealthMonitorLevel.OFF){
            logger.finest("Starting health monitor");
            int delaySeconds = node.getGroupProperties().HEALTH_MONITORING_DELAY_SECONDS.getInteger();
            new HealthMonitor(this,healthLevel,delaySeconds).start();
        }
    }

    public ManagementService getManagementService(){
        return managementService;
    }

    public ThreadMonitoringService getThreadMonitoringService() {
        return threadMonitoringService;
    }

    public String getName() {
        return name;
    }

    public <K, V> IMap<K, V> getMap(String name) {
        if (name == null) {
            throw new NullPointerException("Retrieving a map instance with a null name is not allowed!");
        }
        return getDistributedObject(MapService.SERVICE_NAME, name);
    }

    public <E> IQueue<E> getQueue(String name) {
        if (name == null) {
            throw new NullPointerException("Retrieving a queue instance with a null name is not allowed!");
        }
        return getDistributedObject(QueueService.SERVICE_NAME, name);
    }

    public <E> ITopic<E> getTopic(String name) {
        if (name == null) {
            throw new NullPointerException("Retrieving a topic instance with a null name is not allowed!");
        }
        return getDistributedObject(TopicService.SERVICE_NAME, name);
    }

    public <E> ISet<E> getSet(String name) {
        if (name == null) {
            throw new NullPointerException("Retrieving a set instance with a null name is not allowed!");
        }
        return getDistributedObject(SetService.SERVICE_NAME, name);
    }

    public <E> IList<E> getList(String name) {
        if (name == null) {
            throw new NullPointerException("Retrieving a list instance with a null name is not allowed!");
        }
        return getDistributedObject(ListService.SERVICE_NAME, name);
    }

    public <K, V> MultiMap<K, V> getMultiMap(String name) {
        if (name == null) {
            throw new NullPointerException("Retrieving a multi-map instance with a null name is not allowed!");
        }
        return getDistributedObject(MultiMapService.SERVICE_NAME, name);
    }

    public <K, V> ReplicatedMap<K, V> getReplicatedMap(String name) {
        if (name == null) {
            throw new NullPointerException("Retrieving a replicated-map instance with a null name is not allowed!");
        }
        return getDistributedObject(ReplicatedMapService.SERVICE_NAME, name);
    }

    @Deprecated
    public ILock getLock(Object key) {
        //this method will be deleted in the near future.
        if (key == null) {
            throw new NullPointerException("Retrieving a lock instance with a null key is not allowed!");
        }
        return getDistributedObject(LockService.SERVICE_NAME, LockProxy.convertToStringKey(key, node.getSerializationService()));
    }

    public ILock getLock(String key) {
        if (key == null) {
            throw new NullPointerException("Retrieving a lock instance with a null key is not allowed!");
        }
        return getDistributedObject(LockService.SERVICE_NAME, key);
    }

    public <T> T executeTransaction(TransactionalTask<T> task) throws TransactionException {
        return executeTransaction(TransactionOptions.getDefault(), task);
    }

    public <T> T executeTransaction(TransactionOptions options, TransactionalTask<T> task) throws TransactionException {
        return node.nodeEngine.getTransactionManagerService().executeTransaction(options, task);
    }

    public TransactionContext newTransactionContext() {
        return newTransactionContext(TransactionOptions.getDefault());
    }

    public TransactionContext newTransactionContext(TransactionOptions options) {
        return node.nodeEngine.getTransactionManagerService().newTransactionContext(options);
    }

    public IExecutorService getExecutorService(final String name) {
        if (name == null) {
            throw new NullPointerException("Retrieving an executor instance with a null name is not allowed!");
        }
        return getDistributedObject(DistributedExecutorService.SERVICE_NAME, name);
    }

    public IdGenerator getIdGenerator(final String name) {
        if (name == null) {
            throw new NullPointerException("Retrieving an id-generator instance with a null name is not allowed!");
        }
        return getDistributedObject(IdGeneratorService.SERVICE_NAME, name);
    }

    public IAtomicLong getAtomicLong(final String name) {
        if (name == null) {
            throw new NullPointerException("Retrieving an atomic-long instance with a null name is not allowed!");
        }
        return getDistributedObject(AtomicLongService.SERVICE_NAME, name);
    }

    public <E> IAtomicReference<E> getAtomicReference(final String name) {
        if (name == null) {
            throw new NullPointerException("Retrieving an atomic-reference instance with a null name is not allowed!");
        }
        return getDistributedObject(AtomicReferenceService.SERVICE_NAME, name);
    }

    public ICountDownLatch getCountDownLatch(final String name) {
        if (name == null) {
            throw new NullPointerException("Retrieving a countdown-latch instance with a null name is not allowed!");
        }
        return getDistributedObject(CountDownLatchService.SERVICE_NAME, name);
    }

    public ISemaphore getSemaphore(final String name) {
        if (name == null) {
            throw new NullPointerException("Retrieving a semaphore instance with a null name is not allowed!");
        }
        return getDistributedObject(SemaphoreService.SERVICE_NAME, name);
    }

    public Cluster getCluster() {
        return node.clusterService.getClusterProxy();
    }

    public Member getLocalEndpoint() {
        return node.clusterService.getLocalMember();
    }

    public Collection<DistributedObject> getDistributedObjects() {
        return node.nodeEngine.getProxyService().getAllDistributedObjects();
    }

    public Config getConfig() {
        return node.getConfig();
    }

    public ConcurrentMap<String, Object> getUserContext() {
        return userContext;
    }

    public PartitionService getPartitionService() {
        return node.partitionService.getPartitionServiceProxy();
    }

    public ClientService getClientService() {
        return node.clientEngine.getClientService();
    }

    public LoggingService getLoggingService() {
        return node.loggingService;
    }

    public LifecycleServiceImpl getLifecycleService() {
        return lifecycleService;
    }

    @Override
    public void shutdown() {
        getLifecycleService().shutdown();
    }

    @Override
    @Deprecated
    public <T extends DistributedObject> T getDistributedObject(String serviceName, Object id) {
        if (id instanceof String) {
            return (T) node.nodeEngine.getProxyService().getDistributedObject(serviceName, (String) id);
        }
        throw new IllegalArgumentException("'id' must be type of String!");
    }

    @Override
    public <T extends DistributedObject> T getDistributedObject(String serviceName, String name) {
        return (T) node.nodeEngine.getProxyService().getDistributedObject(serviceName, name);
    }

    public String addDistributedObjectListener(DistributedObjectListener distributedObjectListener) {
        final ProxyService proxyService = node.nodeEngine.getProxyService();
        return proxyService.addProxyListener(distributedObjectListener);
    }

    public boolean removeDistributedObjectListener(String registrationId) {
        final ProxyService proxyService = node.nodeEngine.getProxyService();
        return proxyService.removeProxyListener(registrationId);
    }

    public ThreadGroup getThreadGroup() {
        return threadGroup;
    }

    public final SerializationService getSerializationService() {
        return node.getSerializationService();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof HazelcastInstance)) return false;

        HazelcastInstance that = (HazelcastInstance) o;
        return !(name != null ? !name.equals(that.getName()) : that.getName() != null);
    }

    @Override
    public int hashCode() {
        return name != null ? name.hashCode() : 0;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("HazelcastInstance");
        sb.append("{name='").append(name).append('\'');
        sb.append(", node=").append(node.getThisAddress());
        sb.append('}');
        return sb.toString();
    }
}
