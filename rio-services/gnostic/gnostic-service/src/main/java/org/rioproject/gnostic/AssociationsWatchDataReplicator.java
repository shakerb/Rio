/*
 * Copyright to the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.rioproject.gnostic;

import net.jini.core.entry.Entry;
import net.jini.export.Exporter;
import net.jini.id.Uuid;
import net.jini.jeri.BasicILFactory;
import net.jini.jeri.BasicJeriExporter;
import net.jini.jeri.tcp.TcpServerEndpoint;
import org.rioproject.associations.Association;
import org.rioproject.deploy.ServiceBeanInstance;
import org.rioproject.deploy.ServiceBeanInstantiator;
import org.rioproject.cybernode.Cybernode;
import org.rioproject.cybernode.CybernodeAdmin;
import org.rioproject.entry.OperationalStringEntry;
import org.rioproject.deploy.DeployAdmin;
import org.rioproject.monitor.ProvisionMonitor;
import org.rioproject.opstring.OperationalStringException;
import org.rioproject.opstring.OperationalStringManager;
import org.rioproject.opstring.ServiceElement;
import org.rioproject.resources.servicecore.Service;
import org.rioproject.admin.ServiceAdmin;
import org.rioproject.resources.util.RMIServiceNameHelper;
import org.rioproject.sla.RuleMap;
import org.rioproject.sla.SLA;
import org.rioproject.watch.*;

import java.io.IOException;
import java.rmi.NotBoundException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.ExportException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watch data replicator used by Gnostic in order to feed the CEPSession engine
 * with {@link Calculable}s. Also sets up Drools session.
 *
 * @author Dennis Reedy
 */
class AssociationsWatchDataReplicator implements RemoteWatchDataReplicator {
    private WatchDataReplicator wdr;
    private Exporter exporter;
    private final CEPSession cepSession;
    private final ProvisionMonitor monitor;
    private final Map<WatchDataSource, ServiceElement> watchDataSources = new HashMap<WatchDataSource, ServiceElement>();
    private static final BlockingQueue<Calculable> calculablesQ =
        new LinkedBlockingQueue<Calculable>();
    private ExecutorService execService;
    private final DeployedServiceContext context;
    private Logger logger = Logger.getLogger(AssociationsWatchDataReplicator.class.getName());

    public AssociationsWatchDataReplicator(CEPSession cepSession,
                                           DeployedServiceContext context,
                                           ProvisionMonitor monitor) {
        this.cepSession = cepSession;
        this.context = context;
        this.monitor = monitor;
    }

    public List<ServiceHandle> init(RuleMap ruleMap,
                                    List<Association<Object>> associations) throws ExportException {
        List<ServiceHandle> serviceHandles = new ArrayList<ServiceHandle>();
        if(monitor==null) {
            logger.warning("No ProvisionMonitor reference, unable to initialize");
            return serviceHandles;
        }
        execService = Executors.newSingleThreadExecutor();
        execService.submit(new CEPWorker());

        createWatchDataReplicator();
        logger.info(String.format("Created WatchDataReplicator proxy for %s", ruleMap));

        /* Get ServiceHandles */
        for (Association<Object> association : associations) {
            logger.info(String.format("Number of [%s] instances: %d",
                                      association.getName(), association.getServiceCount()));
            for (Object o : association) {
                ServiceHandle sHandle = getServiceHandle(o,
                                                         getWatches(association, ruleMap),
                                                         association.getServiceItem().attributeSets);
                if(sHandle!=null)
                    serviceHandles.add(sHandle);
            }
        }
        return serviceHandles;
    }

    void addService(Object o, Association<Object> association, RuleMap ruleMap) {
        ServiceHandle sHandle = getServiceHandle(o,
                                                 getWatches(association, ruleMap),
                                                 association.getServiceItem().attributeSets);
        if(sHandle!=null)
            registerWatches(sHandle);
    }

    void registerWatches(ServiceHandle handle) {
        try {
            for(Map.Entry<String, WatchDataSource> entry : handle.getWatchMap().entrySet()) {
                WatchDataSource wds = entry.getValue();
                wds.addWatchDataReplicator(wdr);
                watchDataSources.put(wds, handle.getElem());
                if(logger.isLoggable(Level.FINER))
                    logger.finer(String.format("Subscribed to Watch [%s], service [%s]",
                                               entry.getKey(), handle.getElem().getName()));
            }
            context.addDeployedService(handle.getElem(), handle.getOpMgr());
        } catch (RemoteException e) {
            logger.log(Level.WARNING,
                       "Could not add AssociationsWatchDataReplicator to remote WatchDataSource.", e);
        }
    }

    private List<String> getWatches(Association<Object> association, RuleMap ruleMap) {
        List<String> watches = new ArrayList<String>();
        for(RuleMap.ServiceDefinition def : ruleMap.getServiceDefinitions()) {
            if(def.getServiceName().equals(association.getName())) {
                if(def.getOpStringName()!=null) {
                    if(def.getOpStringName().equals(association.getOperationalStringName())) {
                        watches.addAll(def.getWatches());
                        break;
                    }
                } else {
                    watches.addAll(def.getWatches());
                    break;
                }
            }
        }
        if(logger.isLoggable(Level.FINER))
            logger.finer(String.format("Associated service [%s] has the following watches %s",
                                       association.getName(), watches));
        return watches;
    }
    
    private void createWatchDataReplicator() throws ExportException {
        exporter = new BasicJeriExporter(TcpServerEndpoint.getInstance(0),
                                         new BasicILFactory(),
                                         false,
                                         true);
        RemoteWatchDataReplicator backend = (RemoteWatchDataReplicator) exporter.export(this);
        wdr = WatchDataReplicatorProxy.getInstance(backend, UUID.randomUUID());
    }

    private ServiceHandle getServiceHandle(Object o, List<String> watches, Entry[] entries) {
        ServiceHandle handle = new ServiceHandle();
        if(o instanceof Service) {
            Service s = (Service)o;
            try {
                ServiceElement elem = ((ServiceAdmin)s.getAdmin()).getServiceElement();
                OperationalStringManager opMgr = ((DeployAdmin)monitor.getAdmin()).getOperationalStringManager(elem.getOperationalStringName());
                handle.setElem(elem);
                handle.setOpMgr(opMgr);
                for(String watch : watches) {
                    try {
                        WatchDataSource wds = s.fetch(watch);
                        if(logger.isLoggable(Level.FINER)) {
                            logger.finer(String.format("WatchDataSource for watch [%s]: %s", watch, wds));
                        }
                        if(wds==null)
                            continue;
                        handle.addToWatchMap(watch, wds);
                        ThresholdValues tVals = wds.getThresholdValues();
                        if(tVals instanceof SLA) {
                            handle.addToSLAMap(watch, (SLA)tVals);
                        }
                    } catch (RemoteException e) {
                        logger.log(Level.WARNING,
                                   "Could not add AssociationsWatchDataReplicator to remote WatchDataSource.", e);
                    }
                }
            } catch (RemoteException e) {
                handle = null;
                logger.log(Level.WARNING, "Could not get ServiceElement or OperationalStringManager.", e);

            } catch (OperationalStringException e) {
                handle = null;
                logger.log(Level.SEVERE,
                           "Could not get OperationalStringManager, unable to create AssociationsWatchDataReplicator.",
                           e);
            }
        } else {
            String opStringName = null;
            for(Entry e : entries) {
                if(e instanceof OperationalStringEntry) {
                    opStringName = ((OperationalStringEntry)e).name;
                    break;
                }
            }
            logger.info(String.format("OperationalString for service: [%s]", opStringName));
            if(opStringName!=null) {
                try {
                    OperationalStringManager opMgr = ((DeployAdmin)monitor.getAdmin()).getOperationalStringManager(opStringName);
                    ServiceElement elem = opMgr.getServiceElement(o);
                    if(elem==null) {
                        logger.warning(String.format("Unable to obtain ServiceElement for service [%s], cannot create AssociationsWatchDataReplicator.",
                                                     o));
                        return null;
                    }
                    handle.setElem(elem);
                    handle.setOpMgr(opMgr);
                    Cybernode c = null;

                    for(ServiceBeanInstance sbi : opMgr.getServiceBeanInstances(elem)) {
                        if(o.equals(sbi.getService())) {
                            elem.setServiceBeanConfig(sbi.getServiceBeanConfig());
                            Uuid uuid = sbi.getServiceBeanInstantiatorID();
                            for(ServiceBeanInstantiator s : monitor.getServiceBeanInstantiators()) {
                                if(uuid.equals(s.getInstantiatorUuid())) {
                                    c = (Cybernode)s;
                                    break;
                                }
                            }
                        }
                    }
                    if(c!=null) {
                        Watchable w;
                        if(elem.forkService()) {
                            int registryPort = ((CybernodeAdmin)c.getAdmin()).getRegistryPort();
                            String address = c.getInetAddress().getHostAddress();
                            Registry registry = LocateRegistry.getRegistry(address, registryPort);
                            Remote r = null;
                            NotBoundException notBound = null;
                            //for(int i=0; i<3; i++) {
                                try {
                                    r = registry.lookup(RMIServiceNameHelper.createBindName(elem));
                                } catch (NotBoundException e) {
                                    notBound = e;
                                    //try {
                                    //    Thread.sleep(1000);
                                    //} catch (InterruptedException e1) {
                                    //    e1.printStackTrace();
                                    //}
                                }
                            //}
                            if(r==null) {
                                logger.log(Level.SEVERE,
                                           "Could not get ServiceBeanExecutor, unable to create AssociationsWatchDataReplicator.",
                                           notBound);
                                return null;
                            }
                            if(r instanceof Watchable) {
                                w = (Watchable)r;
                            } else {
                                logger.warning(String.format("Could not get Watchable from Registry at " +
                                                             "[%s:%d], unable to create AssociationsWatchDataReplicator " +
                                                             "for service [%s]",
                                                             address, registryPort, elem.getName()));
                                return null;
                            }
                        } else {
                            w = c;
                        }

                        for(String watch : watches) {
                            try {
                                WatchDataSource wds = w.fetch(watch);
                                if(logger.isLoggable(Level.FINER))
                                    logger.finer(String.format("WatchDataSource for watch [%s]: %s", watch, wds));
                                if(wds==null)
                                    continue;
                                handle.addToWatchMap(watch, wds);
                                ThresholdValues tVals = wds.getThresholdValues();
                                if(tVals instanceof SLA) {
                                    handle.addToSLAMap(watch, (SLA)tVals);
                                }
                            } catch (RemoteException e) {
                                logger.log(Level.WARNING,
                                           "Could not add AssociationsWatchDataReplicator to remote WatchDataSource.", e);
                            }
                        }

                    } else {
                        logger.warning(String.format("Unable to obtain Cybernode for service [%s]", elem.getName()));
                    }
                } catch (RemoteException e) {
                    handle = null;
                    logger.log(Level.WARNING, "Could not get ServiceElement or OperationalStringManager.", e);
                } catch (OperationalStringException e) {
                    handle = null;
                    logger.log(Level.SEVERE,
                               "Could not get OperationalStringManager, unable to create AssociationsWatchDataReplicator.",
                               e);
                } catch (ClassNotFoundException e) {
                    handle = null;
                    logger.log(Level.SEVERE,
                               "Could not get service proxy from monitor, unable to create AssociationsWatchDataReplicator.",
                               e);
                } catch (IOException e) {
                    handle = null;
                    logger.log(Level.SEVERE,
                               "Could not get service proxy from monitor, unable to create AssociationsWatchDataReplicator.",
                               e);
                }
            }
        }
        return handle;
    }

    public void close() {
        if(execService!=null)
            execService.shutdownNow();
        try {
            if (wdr != null) {
                for (Map.Entry<WatchDataSource, ServiceElement> entry : watchDataSources.entrySet()) {
                    WatchDataSource wds = entry.getKey();
                    ServiceElement elem = entry.getValue();
                    try {
                        wds.removeWatchDataReplicator(wdr);
                        logger.info(String.format("Unregistered from Watch [%s], service [%s]", wds.getID(), elem.getName()));
                    } catch (RemoteException e) {
                        if(logger.isLoggable(Level.FINER))
                            logger.finer(String.format("Non-fatal problem unregistering from remote WatchDataSource, most likely " +
                                                       "the service is no longer present. %s:%s",
                                                       e.getClass().getName(), e.getMessage()));
                    }
                }
            }

        } finally {
            if (exporter != null)
                exporter.unexport(true);
            wdr = null;
        }
    }

    public void replicate(Calculable calculable) {
        if(logger.isLoggable(Level.FINEST)) {
            logger.log(Level.FINEST, "Inserted into CEP engine event [{0}]", new Object[]{calculable});
        }
        calculablesQ.add(calculable);
    }

    public void bulkReplicate(Collection<Calculable> calculables) {
        for (Calculable calculable : calculables)
            replicate(calculable);
    }

    class CEPWorker implements Runnable {
        public void run() {
            while (true) {
                Calculable calculable;
                try {
                    calculable = calculablesQ.take();
                } catch (InterruptedException e) {
                    if(logger.isLoggable(Level.FINER))
                        logger.finer("CEPWorker breaking out of main loop: have been Interrupted");
                    break;
                }
                if(calculable!=null) {
                    cepSession.insert(calculable);
                }
            }
        }
    }
}
