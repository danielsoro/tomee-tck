/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.openejb.cts;

import com.sun.ts.lib.deliverable.DeliverableFactory;
import com.sun.ts.lib.deliverable.PropertyManagerInterface;
import com.sun.ts.lib.deliverable.PropertyNotSetException;
import com.sun.ts.lib.porting.DeploymentInfo;
import com.sun.ts.lib.porting.TSDeploymentException;
import com.sun.ts.lib.porting.TSDeploymentInterface2;

import javax.enterprise.deploy.spi.DeploymentManager;
import javax.enterprise.deploy.spi.Target;
import javax.enterprise.deploy.spi.TargetModuleID;
import javax.enterprise.deploy.spi.status.ProgressObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.Hashtable;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.apache.openejb.config.RemoteServer;

public class DeploymentImpl implements TSDeploymentInterface2 {
    private static final String HEAD = "OpenEJB - ";
    private static final String FILENAME = "filename";
    private static final String ALT_DD = "altDD";
    private static final String PATH_SEP = System.getProperty("path.separator", ":");
    private static final String CLIENT_MAIN = "org.apache.openejb.client.Main";

    static {
        final RemoteServer remoteServer = new RemoteServer(120, true);
        remoteServer.start();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                remoteServer.destroy();
            }
        });
    }

    private PrintWriter log;
    private File appsDir;
    private File libDir;

    public void init(PrintWriter log) {
        this.log = log;

        PropertyManagerInterface propMgr = null;
        try {
            propMgr = DeliverableFactory.getDeliverableInstance().getPropertyManager();

            String openejbHomeName = propMgr.getProperty("openejb.home");
            if (openejbHomeName == null) {
                throw new IllegalStateException("Not initialized; missing property: geronimo.porting.planDir");
            }
            File openejbHome = new File(openejbHomeName).getCanonicalFile();
            System.setProperty("openejb.home", openejbHome.getAbsolutePath());
            appsDir = new File(openejbHome, "apps");
            libDir = new File(openejbHome, "lib");

            try {
                String openejbUri = propMgr.getProperty("openejb.server.uri");
                System.setProperty("openejb.uri", openejbUri);
            } catch (PropertyNotSetException e) {
            }

            try {
                String value = propMgr.getProperty("ts.run.classpath");
                System.setProperty("ts.run.classpath", value);
            } catch (PropertyNotSetException e) {
            }

            this.log.println(HEAD + "Initialized Deployment helper");
        } catch (Exception e) {
            this.log.println(HEAD + "ERROR initializing DeploymentImpl");
            e.printStackTrace(this.log);
            throw new AssertionError(e);
        }
    }

    public Hashtable getDependentValues(DeploymentInfo[] infoArray) {
        return new Hashtable();
    }

    public InputStream getDeploymentPlan(DeploymentInfo info) throws TSDeploymentException {
        String earPath = info.getEarFile();
        if (earPath == null) {
            throw new TSDeploymentException("EarFile is null");
        }
        log.println(HEAD + "module: " + earPath);

        Properties properties = new Properties();
        properties.put(FILENAME, earPath);

        Set<String> moduleIds = new TreeSet<String>();
        moduleIds.addAll(info.getWebRuntimeData().keySet());
        moduleIds.addAll(info.getEjbRuntimeData().keySet());
        moduleIds.addAll(info.getAppRuntimeData().keySet());
        moduleIds.addAll(info.getAppClientRuntimeData().keySet());

        for (String path : info.getRuntimeFiles()) {
            if (path.startsWith(earPath)) {
                String name = path.substring(earPath.length() + 1);
                properties.put(ALT_DD + "/" + name, path);
            } else {
                String fileName = new File(path).getName();
                for (String moduleId : moduleIds) {
                    if (fileName.startsWith(moduleId)) {
                        String name = fileName.substring(moduleId.length() + 1);
                        properties.put(ALT_DD + "/" + moduleId + "/" + name, path);
                    }
                }
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            properties.store(out, "Auto Generated Deployment Plan");
        } catch (IOException e) {
            throw new TSDeploymentException("Unable to create deployment plan", e);
        }
        return new ByteArrayInputStream(out.toByteArray());
    }

    public Target[] getTargetsToUse(Target[] targets, DeploymentInfo info) {
        return new Target[]{targets[0]};
    }

    public String getAppClientArgs(Properties p) {
        String executeArgs = p.getProperty("executeArgs");
        String earFile = p.getProperty("ear_file");
        earFile = new File(earFile).getName();
        earFile = earFile.substring(0, earFile.lastIndexOf('.'));
        String clientname = p.getProperty("client_name");
        File appClientJar = new File(new File(appsDir, earFile), clientname + ".jar");
        String classPath = appClientJar.getAbsolutePath();

        String property = System.getProperty("ts.run.classpath");
        classPath += PATH_SEP + property;

//        for (int i = 0; i < libDir.listFiles().length; i++) {
//            File file = libDir.listFiles()[i];
//            if (file.getName().endsWith(".jar")) {
//                classPath += PATH_SEP + file.getAbsolutePath();
//            }
//        }
        return "-cp " + classPath + " -Dopenejb.client.moduleId=" + clientname + " " + CLIENT_MAIN + " " + executeArgs;
    }

    public String getClientClassPath(TargetModuleID[] targetIDs, DeploymentInfo info, DeploymentManager manager) throws TSDeploymentException {
        return "";
    }

    public void createConnectionFactory(TargetModuleID[] targetIDs, Properties p) throws TSDeploymentException {
    }

    public void removeConnectionFactory(TargetModuleID[] targetIDs, Properties p) throws TSDeploymentException {
    }

    public void postDistribute(ProgressObject po) {
        TargetModuleID moduleID = po.getResultTargetModuleIDs()[0];
        log.println(HEAD + "Distribute returned moduleID " + moduleID.getModuleID());
    }

    public void postStart(ProgressObject po) {
    }

    public void postStop(ProgressObject po) {
    }

    public void postUndeploy(ProgressObject po) {
    }
}
