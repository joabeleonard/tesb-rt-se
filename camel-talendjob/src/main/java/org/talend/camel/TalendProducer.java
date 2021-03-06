/*
 * #%L
 * Talend ESB :: Camel Talend Job Component
 * %%
 * Copyright (C) 2011 - 2014 Talend Inc.
 * %%
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
 * #L%
 */

package org.talend.camel;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.impl.DefaultProducer;
import org.apache.camel.util.ObjectHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import routines.system.api.TalendESBRoute;
import routines.system.api.TalendJob;

/**
 * <p>
 * The Talend producer.
 * </p>
 */
public class TalendProducer extends DefaultProducer {

    private static final transient Logger LOG = LoggerFactory.getLogger(TalendProducer.class);

    private Thread workingThread;
    private TalendJob jobInstance;

    public TalendProducer(TalendEndpoint endpoint) {
        super(endpoint);
    }

    public void process(Exchange exchange) throws Exception {
        final TalendEndpoint talendEndpoint = (TalendEndpoint) getEndpoint();
        final String context = talendEndpoint.getContext();
        final Collection<String> args = new ArrayList<String>();
        if (context != null) {
            args.add("--context=" + context);
        }
        if (talendEndpoint.isPropagateHeader()) {
            getParamsFromHeaders(exchange, args);
        }
        getParamsFromProperties(getEndpoint().getCamelContext().getProperties(), args);
        getParamsFromProperties(talendEndpoint.getEndpointProperties(), args);
        boolean success = false;
        TalendJob jobInstance = getJobInstance();
        try {
            invokeTalendJob(jobInstance, args.toArray(new String[args.size()]), exchange);
            jobDone();
            success = true;
        } finally {
            if (!success) {
                jobDown();
            }
        }
    }

    private static void getParamsFromProperties(Map<String, String> propertiesMap, Collection<String> args) {
        if (propertiesMap != null) {
            for (Map.Entry<String, String> entry : propertiesMap.entrySet()) {
                args.add("--context_param " + entry.getKey() + '=' + entry.getValue());
            }
        }
    }

    private static void getParamsFromHeaders(
            Exchange exchange, Collection<String> args) {
        Map<String, Object> headers = exchange.getIn().getHeaders();
        for (Map.Entry<String, Object> header : headers.entrySet()) {
            Object headerValue = header.getValue();
            if (headerValue != null) {
                String headerStringValue = exchange.getContext().getTypeConverter()
                        .convertTo(String.class, exchange, headerValue);
                args.add("--context_param " + header.getKey() + '=' + headerStringValue);
            }
        }
    }

    private void invokeTalendJob(final TalendJob jobInstance, String[] args, Exchange exchange) {
        try {
            final Method setExchangeMethod =
                    jobInstance.getClass().getMethod("setExchange", new Class[]{Exchange.class});
            LOG.debug("Pass the exchange from route to Job");
            ObjectHelper.invokeMethod(setExchangeMethod, jobInstance, exchange);
        } catch (NoSuchMethodException e) {
            LOG.debug("No setExchange(exchange) method found in Job, the message data will be ignored");
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Invoking Talend job '" + jobInstance.getClass().getCanonicalName() 
                    + ".runJob(String[] args)' with args: " + Arrays.toString(args));
        }

        // use local variable due to single component instance during parallel processing
        final Thread thread = Thread.currentThread();
        workingThread = thread;
        final ClassLoader oldContextCL = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(jobInstance.getClass().getClassLoader());
            int result = jobInstance.runJobInTOS(args);
            if (result != 0) {
                throw new RuntimeCamelException("Execution of Talend job '" 
                        + jobInstance.getClass().getCanonicalName() + "' with args: "
                        + Arrays.toString(args) + "' failed, see stderr for details");
                // Talend logs errors using System.err.println
            }
        } finally {
            thread.setContextClassLoader(oldContextCL);
            workingThread = null;
        }
    }

    @Override
    protected void doStop() throws Exception {
        super.doStop();
        boolean success = false;
        try {
            TalendJob wjob = jobInstance;
            if (wjob instanceof TalendESBRoute) {
                ((TalendESBRoute) wjob).stop();
                LOG.info("Job instance stopped.");
                wait(100L);
            }
            success = true;
        } finally {
            Thread wthread = workingThread;
            if (null != wthread) {
                LOG.info("Enforce Talend job termination.");
                wthread.interrupt();
            }
            if (!success) {
            	jobDown();
            }
        }
    }

    @Override
    protected void doShutdown() throws Exception {
    	super.doShutdown();
    	jobDown();
    }

    private TalendJob getJobInstance() throws Exception {
        if (jobInstance == null) {
            jobInstance = ((TalendEndpoint) getEndpoint()).getJobInstance();
            LOG.debug("Getting new job instance.");
        } else {
            LOG.debug("Re-using sticky job instance.");
        }
        return jobInstance;
    }

    private void jobDone() throws Exception {
        if (!((TalendEndpoint) getEndpoint()).isStickyJob()) {
            jobDown();
        }
    }

    private void jobDown() throws Exception {
    	TalendJob job = jobInstance;
    	jobInstance = null;
    	if (job instanceof TalendESBRoute) {
    		((TalendESBRoute) job).shutdown();
            LOG.info("Job instance shut down.");
    	}
    }
}
