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
package org.rioproject.cybernode;

import com.sun.jini.admin.DestroyAdmin;
import com.sun.jini.start.LifeCycle;
import groovy.lang.MissingMethodException;
import net.jini.admin.Administrable;
import net.jini.config.Configuration;
import net.jini.config.ConfigurationException;
import net.jini.id.Uuid;
import org.rioproject.admin.ServiceBeanControlException;
import org.rioproject.associations.Association;
import org.rioproject.associations.AssociationManagement;
import org.rioproject.associations.AssociationType;
import org.rioproject.bean.BeanHelper;
import org.rioproject.bean.Initialized;
import org.rioproject.bean.Started;
import org.rioproject.config.Constants;
import org.rioproject.core.jsb.DiscardManager;
import org.rioproject.core.jsb.ServiceBeanContext;
import org.rioproject.deploy.ServiceRecord;
import org.rioproject.costmodel.ResourceCost;
import org.rioproject.cybernode.exec.ServiceBeanExecManager;
import org.rioproject.deploy.ServiceBeanInstance;
import org.rioproject.deploy.ServiceBeanInstantiationException;
import org.rioproject.event.EventHandler;
import org.rioproject.exec.ServiceExecutor;
import org.rioproject.jmx.JMXUtil;
import org.rioproject.jmx.MBeanServerFactory;
import org.rioproject.jsb.*;
import org.rioproject.logging.WrappedLogger;
import org.rioproject.opstring.OpStringManagerProxy;
import org.rioproject.opstring.OperationalStringManager;
import org.rioproject.opstring.ServiceElement;
import org.rioproject.resources.util.ThrowableUtil;
import org.rioproject.sla.SLAThresholdEvent;
import org.rioproject.system.ComputeResource;
import org.rioproject.system.ComputeResourceUtilization;
import org.rioproject.system.capability.PlatformCapability;

import javax.management.ObjectName;
import java.lang.reflect.Method;
import java.rmi.RemoteException;
import java.util.*;
import java.util.logging.Level;

/**
 * The JSBDelegate provides loading and management services for a service
 * which has been instantiated by a {@link ServiceBeanContainer}
 *
 * @author Dennis Reedy
 */
public class JSBDelegate implements ServiceBeanDelegate {
    /** ServiceBeanInstance of a loaded and started ServiceBean */
    private ServiceBeanInstance instance;
    /** Unique identifier for the delegate */
    private Object identifier;
    /** ServiceBean ID */
    private Uuid serviceID;
    /** ServiceBean proxy */
    private Object serviceProxy;
    /** Reference to the ServiceBeanContainer */
    private ServiceBeanContainer container;
    /** The ServiceElement */
    private ServiceElement sElem;
    /** The OperationalStringManager for the JSB */
    private OperationalStringManager opStringMgr;
    /** The ThreadGroup the JSB is started in */
    //private ThreadGroup threadGroup;
    /** Throwable for ThreadGroup processing */
    private Throwable abortThrowable;
    /** The ServiceRecord */
    private ServiceRecord serviceRecord;
    private final Object serviceRecordLock = new Object();
    private long lastServiceRecordUpdate;
    /** Flag to indicate the service is in the process of starting */
    private boolean starting = false;
    /** Flag to indicate the service is in the process of terminating */
    private boolean terminating=false;
    /** Flag to indicate the service is terminated */
    private boolean terminated=false;
    /** A utility used to install staged data */
    private StagedDataManager stagedDataManager;
    /** The ServiceBeanContext */
    private ServiceBeanContext context;
    /** EventHandler for SLAThresholdEvent processing */
    private EventHandler slaEventHandler;
    /* Manage declared SLAs */
    private ServiceBeanSLAManager serviceBeanSLAManager;
    /** A ServiceElementChangeManager for handling updates to the
     * ServiceElement */
    private ServiceElementChangeManager sElemChangeMgr;
    private ServiceCostCalculator serviceCostCalculator;
    //private ServiceProvisionEvent provisionEvent;
    private ServiceBeanExecManager execManager;
    private final Collection<PlatformCapability> installedPlatformCapabilities =
        new ArrayList<PlatformCapability>();
    private static final String CONFIG_COMPONENT = "org.rioproject.cybernode";
    /** Logger */
    private static WrappedLogger logger = WrappedLogger.getLogger(CONFIG_COMPONENT);
    /** Result from loading the service */
    protected ServiceBeanLoader.Result loadResult;

    /**
     * Create a JSBDelegate
     *
     * @param identifier A cookie for internal accounting
     * @param serviceID Unique identifier for the ServiceBean
     * @param container The ServiceBeanContainer
     */
    public JSBDelegate(Integer identifier, Uuid serviceID, ServiceBeanContainer container) {
        this.identifier = identifier;
        this.serviceID = serviceID;
        this.container = container;
        stagedDataManager = new StagedDataManager(container.getComputeResource());
        ServiceCostCalculator defaultCostCalculator = new ServiceCostCalculator();
        Configuration config = container.getSharedConfiguration();
        try {
            serviceCostCalculator = (ServiceCostCalculator)config.getEntry(CONFIG_COMPONENT,
                                                                           "serviceCostCalculator",
                                                                           ServiceCostCalculator.class,
                                                                           defaultCostCalculator);
        } catch(ConfigurationException e) {
            logger.log(Level.WARNING, "Getting ServiceCostCalculator, using default", e);
            serviceCostCalculator = defaultCostCalculator;
        }
        serviceCostCalculator.setComputeResource(container.getComputeResource());
    }

    public void setOperationalStringManager(OperationalStringManager opStringMgr) {
        this.opStringMgr= opStringMgr;
    }

    public void setServiceElement(ServiceElement sElem) {
        this.sElem = sElem;
        stagedDataManager.setServiceElement(sElem);
    }

    public void setEventHandler(EventHandler slaEventHandler) {
        this.slaEventHandler = slaEventHandler;
    }

    /**
     * Get the ServiceRecord
     * 
     * @return ServiceRecord
     */
    public ServiceRecord getServiceRecord() {
        if(serviceRecord!=null) {
            synchronized(serviceRecordLock) {
                try {
                    long now = System.currentTimeMillis();
                    if(lastServiceRecordUpdate!=0)
                        serviceRecord.setUpdated();
                    long diff = (lastServiceRecordUpdate==0 ? 0 : now-lastServiceRecordUpdate);
                    ResourceCost[] resourceCosts = serviceCostCalculator.calculateCosts(diff);
                    for (ResourceCost resourceCost : resourceCosts) {
                        serviceRecord.addResourceCost(resourceCost);
                    }
                    lastServiceRecordUpdate = now;
                } catch (Throwable t) {
                    logger.log(Level.WARNING, t, "Calculating resource costs for [%s]", sElem.getName());
                }
            }
        }
        return(serviceRecord);
    }

    /**
     * Get the ServiceElement
     * 
     * @return The ServiceElement used by this JSBDelegate 
     */
    public ServiceElement getServiceElement() {
        return(sElem);
    }

    /*
     * Update the ServiceElement and potentially the OperationalStringManager
     * if the ServiceElement is the same as the ServiceElement the delegate
     * has created a ServiceBean instance of
     */
    public boolean update(ServiceElement newElem, OperationalStringManager opMgr) {
        if(execManager!=null) {
            try {
                long waited = 0;
                long totalWait = 10*1000;
                while(execManager.getServiceBeanExecutor()==null && waited<totalWait) {
                    try {
                        Thread.sleep(500);
                        waited += 500;
                    } catch (InterruptedException e) {
                        return false;
                    }
                }
                if(execManager.getServiceBeanExecutor()==null)
                    return false;
                execManager.getServiceBeanExecutor().update(newElem, opMgr);
                return true;
            } catch (RemoteException e) {
                logger.log(Level.WARNING, "Updating forked service ["+sElem.getName()+"]", e);
                return false;
            }
        }
        if(!this.sElem.equals(newElem))
            return(false);
        if(terminated || terminating)
            return(false);
        synchronized(this) {
            if(serviceProxy==null) {
                logger.finest("Cannot update [%s], Proxy is null", sElem.getName());
                return(false);
            }
            /* Preserve instanceID */
            Long instanceID = sElem.getServiceBeanConfig().getInstanceID();
            if(instanceID!=null) {
                sElem = ServiceElementUtil.prepareInstanceID(newElem, true, instanceID);
            } else {
                sElem = ServiceElementUtil.copyServiceElement(newElem);
                logger.warning("No instanceID for [%s] to update", sElem.getName());
            }

            if(context instanceof JSBContext) {
                ((JSBContext)context).setServiceElement(sElem);
            } else {
                logger.warning("Cannot update ["+sElem.getName()+"], Unknown ServiceBeanContext type "+
                               "["+context.getClass().getName()+"]");
                return(false);
            }

            if(context.getServiceBeanManager() instanceof JSBManager) {
                JSBManager jsbMgr = (JSBManager)context.getServiceBeanManager();
                OperationalStringManager mgr = jsbMgr.getOperationalStringManager();
                if(mgr!=null &&
                   !(mgr instanceof OpStringManagerProxy.OpStringManager)) {
                    jsbMgr.setOperationalStringManager(opMgr);
                }                
            } else {
                logger.warning("Cannot update [%s], Unknown ServiceBeanManager type [%s]",
                               sElem.getName(), context.getServiceBeanManager().getClass().getName());
                return(false);
            }
        }
        return(true);
    }

    /**
     * Loads and starts a service, blocking until the service has been started
     * 
     * @return The ServiceBeanInstance
     *
     * @throws org.rioproject.deploy.ServiceBeanInstantiationException if there are errors loading the service
     * bean
     */
    public ServiceBeanInstance load() throws ServiceBeanInstantiationException {
        if(instance!=null)
            return(instance);
        synchronized(this) {
            startServiceBean(sElem, opStringMgr);
        }
        return(instance);
    }

    /**
     * Get the ServiceBeanInstance
     *
     * @return The ServiceBeanInstance
     */
    public ServiceBeanInstance getServiceBeanInstance() {
        return (instance);
    }

    /**
     * Advertise the ServiceBean, making it available to all clients
     *
     * @throws ServiceBeanControlException if the service bean cannot be advertised
     */
    public void advertise() throws ServiceBeanControlException {
        if(execManager!=null)
            return;
        if(serviceProxy==null) {
            throw new ServiceBeanControlException("Cannot advertise ["+sElem.getName()+"], Proxy is null");
        }
        if(terminated || terminating)
            throw new ServiceBeanControlException("advertising service while in the process of terminating");
        /* If any of the associations are of type requires, service
         * advertisement is managed by AssociationManagement */
        for (Association assoc : context.getAssociationManagement().getAssociations()) {
            if (assoc.getAssociationType()== AssociationType.REQUIRES) {
                logger.fine("%s has at least one requires Association, advertisement managed by AssociationManagement",
                            sElem.getName());
                return;
            }
        }
        try {
            ServiceAdvertiser.advertise(serviceProxy, context);
            if(logger.isLoggable(Level.FINE))
                logger.fine(sElem.getName()+": advertised");
        } catch(ServiceBeanControlException e) {
            logger.warning("Could not advertise %s, continue on", sElem.getName());
            throw e;
        }
    }

    public ComputeResourceUtilization getComputeResourceUtilization() {
        ComputeResourceUtilization cru = null;
        if(sElem.forkService()) {
            try {
                cru  = execManager.getServiceBeanExecutor().getComputeResourceUtilization();
            } catch (RemoteException e) {
                logger.warning("Getting compute resource utilization failed for " +
                               "service [%s], the service may be " +
                               "in the process of failing or may have already " +
                               "failed. %s:%s",
                               sElem.getName(), e.getClass().getName(), e.getMessage());
            }
        } else if(loadResult.getImpl() instanceof ServiceExecutor) {
            cru = ((ServiceExecutor)loadResult.getImpl()).getComputeResourceUtilization();
        } else {
            cru = container.getComputeResource().getComputeResourceUtilization();
        }
        return cru;
    }

    /**
     * Terminate the ServiceBeanDelegate. This calls the destroy method of the 
     * ServiceBean
     */
    public void terminate() {
        if(terminated || terminating)
            return;
        try {
            terminating = true;
            if(serviceBeanSLAManager != null) {
                serviceBeanSLAManager.terminate();
                serviceBeanSLAManager = null;
            }

            if(context!=null) {
                /* Remove ServiceElementChangeManager */
                context.getServiceBeanManager().removeListener(sElemChangeMgr);
            }

            ServiceTerminationHelper.cleanup(context);

            if(opStringMgr instanceof OpStringManagerProxy.OpStringManager) {
                ((OpStringManagerProxy.OpStringManager)opStringMgr).terminate();
            }

            /* If we have an instance, go through service termination */
            if(instance!=null) {
                if(serviceProxy!=null) {
                    try {
                        if(serviceProxy instanceof Administrable) {
                            Administrable admin = (Administrable)serviceProxy;
                            Object adminObject = admin.getAdmin();

                            if(adminObject instanceof DestroyAdmin) {
                                DestroyAdmin destroyAdmin = (DestroyAdmin)adminObject;
                                destroyAdmin.destroy();
                                setDiscarded();
                                //container.discarded(identifier);
                                terminated = true;
                            } else {
                                logger.fine("No DestroyAdmin capabilities for %s", serviceProxy.getClass().getName());
                            }
                        } else {
                            logger.fine("No Administrable capabilities for %s", serviceProxy.getClass().getName());
                        }
                    } catch(Throwable t) {
                        logger.log(Level.SEVERE, "Terminating ServiceBean", t);
                        terminating = false;
                    } finally {
                        serviceProxy = null;
                    }
                }
            }

            /* Decrement platform capability counter */
            for(PlatformCapability pCap : installedPlatformCapabilities) {
                pCap.decrementUsage();
            }

            /* Unprovision any installed platform capability components */
            PlatformCapability[] pCaps = stagedDataManager.removeInstalledPlatformCapabilities(false);
            for(PlatformCapability pCap : pCaps) {
                unregisterPlatformCapability(pCap);
            }
            /* Unprovision staged data*/
            stagedDataManager.removeStagedData();
        } finally {
            container.remove(identifier);
            if(loadResult!=null) {
                ServiceBeanLoader.unload(loadResult, sElem);
                loadResult = null;
            }
            if(context!=null && context.getServiceBeanManager() instanceof JSBManager)
                ((JSBManager)context.getServiceBeanManager()).setMarshalledInstance(null);
            context = null;
        }
    }

    /**
     * Load, instantiate and start the ServiceBean
     *
     * @param sElem The ServiceElement
     * @param opStringMgr The OperationalStringManager
     *
     * @throws org.rioproject.deploy.ServiceBeanInstantiationException if there are errors creating the
     * service bean
     */
    protected void startServiceBean(final ServiceElement sElem, final OperationalStringManager opStringMgr)
    throws ServiceBeanInstantiationException {
        /*
        threadGroup = new ThreadGroup("ServiceBeanThreadGroup:"+sElem.getName()) {
            public void uncaughtException(Thread t, Throwable o) {
                logger.log(Level.SEVERE,
                           "UncaughtException thrown in ServiceBean "+
                           "["+sElem.getName()+"]",
                           o);
            }
        };

        Thread jsbThread = new Thread(threadGroup, "JSBDelegate") {
        */
        Thread jsbThread = new Thread("JSBDelegate") {
            public void run() {
                starting = true;
                ComputeResource computeResource = container.getComputeResource();
                try {

                    if(!runningForked()) {
                       /* If there are provisionable capabilities, or
                         * data staging, perform the stagedData/installation */
                        stagedDataManager.download();

                        installedPlatformCapabilities.addAll(stagedDataManager.getInstalledPlatformCapabilities());
                        for(PlatformCapability pCap : installedPlatformCapabilities) {
                            pCap.incrementUsage();
                        }
                    }

                    /* Check if we are forking a service bean */
                    if(sElem.forkService() && !runningForked()) {
                        execManager = new ServiceBeanExecManager(identifier, sElem, container);
                        try {
                            /* Get matched PlatformCapability instances to apply */
                            PlatformCapability[] pCaps = computeResource.getPlatformCapabilities();
                            PlatformCapability[] matched = ServiceElementUtil.getMatchedPlatformCapabilities(sElem, pCaps);
                            instance = execManager.exec(sElem, opStringMgr, new JSBDiscardManager(), matched);
                            serviceRecord = execManager.getServiceRecord();
                        } catch (Exception e) {
                            abortThrowable = e;
                        }

                    } else {
                        /* Create the DiscardManager */
                        JSBDiscardManager discardManager = new JSBDiscardManager();

                        /* Create the ServiceBeanManager */
                        JSBManager jsbManager = new JSBManager(sElem,
                                                               opStringMgr,
                                                               computeResource.getAddress().getHostAddress(),
                                                               container.getUuid());
                        jsbManager.setDiscardManager(discardManager);
                        jsbManager.setServiceID(serviceID);
                        /*
                        * Load and start the ServiceBean
                        */
                        loadResult = ServiceBeanLoader.load(sElem, serviceID, jsbManager, container);

                        AssociationManagement associationManagement;
                        ClassLoader currentCL = Thread.currentThread().getContextClassLoader();
                        try {
                            ClassLoader jsbCL = loadResult.getImpl().getClass().getClassLoader();
                            Thread.currentThread().setContextClassLoader(jsbCL);
                            context = loadResult.getServiceBeanContext();
                            associationManagement = context.getAssociationManagement();
                            serviceProxy = loadResult.getMarshalledInstance().get(false);
                        } finally {
                            Thread.currentThread().setContextClassLoader(currentCL);
                        }
                        serviceID = loadResult.getServiceID();
                        
                        /* Set properties to the ServiceCostCalculator */
                        serviceCostCalculator.setDownloadRecords(stagedDataManager.getDownloadRecords());

                        //serviceCostCalculator.setImpl(loadResult.impl);
                        //serviceCostCalculator.setProxy(serviceProxy);
                        serviceCostCalculator.setServiceBeanContext(context);

                        /* Register any PlatformCapability mbeans we created for
                         * the ServiceBean */
                        registerPlatformCapabilities();

                        try {
                            Method setBackend = associationManagement.getClass().getMethod("setBackend", Object.class);
                            setBackend.invoke(associationManagement, loadResult.getImpl());
                        } catch(Exception e) {
                            logger.log(Level.WARNING, e,
                                       "Failed to get setBackend method from ServiceBean [%s] impl", sElem.getName());
                        }
                        associationManagement.setServiceBeanContainer(container);
                        associationManagement.setServiceBeanContext(context);

                        if(context instanceof JSBContext) {
                            EventHandler eH = ((JSBContext)context).getEventTable().get(SLAThresholdEvent.ID);
                            if(eH!=null) {
                                slaEventHandler = eH;
                                logger.fine("Set EventHandler [%s] for SLAManagement for service %s",
                                            slaEventHandler.getClass().getName(), sElem.getName());
                            }
                        }

                        /* Create the ServiceBeanSLAManager */
                        serviceBeanSLAManager = new ServiceBeanSLAManager(loadResult.getImpl(),
                                                                          serviceProxy,
                                                                          context,
                                                                          slaEventHandler);
                        serviceBeanSLAManager.addSLAs(sElem.getServiceLevelAgreements().getServiceSLAs());
                        serviceBeanSLAManager.createSLAThresholdEventAdapter();

                        sElemChangeMgr = new ServiceElementChangeManager(context, serviceBeanSLAManager, serviceProxy);
                        context.getServiceBeanManager().addListener(sElemChangeMgr);


                        /* Invoke postInitialize lifecycle method if defined
                         * (RIO-141) */
                        BeanHelper.invokeLifeCycle(Initialized.class, "postInitialize", loadResult.getImpl());

                        /* Create the ServiceBeanInstance */
                        instance = new ServiceBeanInstance(serviceID,
                                                           loadResult.getMarshalledInstance(),
                                                           context.getServiceBeanConfig(),
                                                           container.getComputeResource().getAddress().getHostAddress(),
                                                           container.getUuid());

                        /* Create the ServiceRecord */
                        serviceRecord = new ServiceRecord(serviceID,
                                                          sElem,
                                                          container.getComputeResource().getAddress().getHostName());
                    }

                    /* If we have not aborted, continue ... */
                    if(abortThrowable==null) {
                        container.started(identifier);
                    } else {
                        throw abortThrowable;
                    }

                } catch(Throwable t) {
                    StringBuilder buff = new StringBuilder();
                    String label = "classpath";
                    if(sElem.getComponentBundle()!=null) {
                        if(sElem.getComponentBundle().getArtifact()==null) {
                            String[] jars = sElem.getComponentBundle().getJARNames();
                            for(int i=0; i<jars.length; i++) {
                                if(i>0)
                                    buff.append(", ");
                                buff.append(sElem.getComponentBundle().getCodebase()).append(jars[i]);
                            }
                        } else {
                            label = "artifact";
                            buff.append(sElem.getComponentBundle().getArtifact());
                        }
                    } else {
                        buff.append("<unknown>");
                    }
                    abortThrowable = ThrowableUtil.getRootCause(t);
                    if(t instanceof MissingMethodException) {
                        MissingMethodException e = (MissingMethodException)t;
                         System.out.println("===> "+sElem.getName()+", MISSING:"+e.getMethod());
                    }
                    logger.log(Level.SEVERE, abortThrowable,
                               "Failed to load the ServiceBean [%s] %s [%s]", sElem.getName(), label, buff.toString());
                    container.remove(identifier);
                }
            }
        };
        jsbThread.start();
        try {
            jsbThread.join();
                logger.finest("ServiceBean [%s] start thread completed", sElem.getName());
        } catch(InterruptedException e) {
            logger.log(Level.WARNING, e, "ServiceBean [%s] start Thread interrupted", sElem.getName());
        } finally {
            starting = false;
        }

        try {
            /* Invoke postStart lifecycle method if defined (RIO-141). Note that
             * we will not have a loadResult.impl of the service if it has been
             * forked/exec'd */
            if(loadResult!=null && loadResult.getImpl()!=null)
                BeanHelper.invokeLifeCycle(Started.class, "postStart", loadResult.getImpl());
        } catch (Exception e) {
            logger.log(Level.WARNING,
                       "Failed to invoke the postStart() lifecycle method on " +
                       "the target bean. At this point this is considered a " +
                       "non-fatal exception",
                       e);                    
        } finally {
            /* Remove the initialization cookie, Make sure to check for the
             * context, similar to the above, we will not have a context for
             * the service if it has been forked/exec'd (RIO-141) */
            if(context!=null) {
                Map<String, Object> configParms =
                    context.getServiceBeanConfig().getConfigurationParameters();
                configParms.remove(Constants.STARTING);
                context.getServiceBeanConfig().setConfigurationParameters(configParms);
            }
        }
        if(abortThrowable != null) {
            for(PlatformCapability pCap : installedPlatformCapabilities) {
                pCap.decrementUsage();
            }
            ServiceBeanInstantiationException toThrow;
            if(abortThrowable instanceof ServiceBeanInstantiationException) {
                toThrow = (ServiceBeanInstantiationException) abortThrowable;
            } else {
                ServiceBeanInstantiationException.ExceptionDescriptor exDesc =
                    new ServiceBeanInstantiationException.ExceptionDescriptor(abortThrowable.getClass().getName(),
                                                                      abortThrowable.getLocalizedMessage(),
                                                                      abortThrowable.getStackTrace());
                toThrow = new ServiceBeanInstantiationException("ServiceBean ["+sElem.getName()+"] instantiation failed",
                                                                exDesc,
                                                                true);
            }
            stagedDataManager.removeInstalledPlatformCapabilities();
            throw toThrow;
        }
    }

    /*
     * Get the created implementation
     */
    Object getImpl() {
        return loadResult.getImpl();
    }


    /*
     * Get the service proxy
     */
    Object getProxy() {
        return serviceProxy;
    }

    /*
     * Register any PlatformCapability mbeans we created for
     * the ServiceBean
     */
    private void registerPlatformCapabilities() {
        for (PlatformCapability pCap :
            stagedDataManager.getInstalledPlatformCapabilities()) {
            try {
                ObjectName objectName =
                    JMXUtil.getObjectName(context,
                                          "",
                                          "PlatformCapability",
                                          pCap.getName());
                MBeanServerFactory.getMBeanServer().registerMBean(pCap,
                                                                  objectName);
            } catch (Exception e) {
                Throwable cause = e;
                if (e.getCause() != null)
                    cause = e.getCause();
                logger.log(Level.WARNING, cause,
                           "Registering PlatformCapability [%s] to JMX", pCap.getName());
            }
        }
    }

    /*
     * Unregister a PlatformCapability from JMX
     */
    private void unregisterPlatformCapability(PlatformCapability pCap) {
        try {
            if(context==null)
                return;
            ObjectName objectName = JMXUtil.getObjectName(context, "", "PlatformCapability", pCap.getName());
            MBeanServerFactory.getMBeanServer().unregisterMBean(objectName);
        } catch(Exception e) {
            logger.log(Level.WARNING, e, "Unregistering PlatformCapability [%s]:%s", pCap.getName(), e.toString());
        }
    }

    /*
     * Set discarded attributes in the ServiceRecord
     */
    private void setDiscarded() {
        /* check if the service is "starting" this may happen if service
         * creation is aborted before the start thread completes */
        if(starting) {
            int iterations = 0;
            while(serviceRecord==null && iterations<4) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    //
                }
                iterations++;
            }
        }
        if(serviceRecord==null) {
            logger.warning("Discarding [%s] service, has no ServiceRecord", sElem.getName());
            return;
        }
        synchronized(serviceRecordLock) {
            serviceRecord.setDiscardedDate(new Date());
            serviceRecord.setType(ServiceRecord.INACTIVE_SERVICE_RECORD);
        }
    }

    private boolean runningForked() {
        return (System.getProperty(Constants.SERVICE_BEAN_EXEC_NAME)!=null);
    }


    /**
     * The JSBDiscardManager provides a mechanism to manage the discarding of a 
     * ServiceBean
     */
    class JSBDiscardManager implements DiscardManager, LifeCycle {

        /**
         * @see org.rioproject.core.jsb.DiscardManager#discard
         */
        public void discard() {
            if(terminated)
                return;
            setDiscarded();
            container.discarded(identifier);
            instance=null;
            terminate();
        }

        /**
         * @see com.sun.jini.start.LifeCycle#unregister
         */
        public boolean unregister(Object impl) {
            discard();
            return(true);
        }
    }

}
