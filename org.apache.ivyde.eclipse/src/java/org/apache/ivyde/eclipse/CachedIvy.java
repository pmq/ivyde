/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.ivyde.eclipse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.text.ParseException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Properties;

import org.apache.ivy.Ivy;
import org.apache.ivy.core.cache.DefaultRepositoryCacheManager;
import org.apache.ivy.core.module.descriptor.ModuleDescriptor;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.plugins.parser.ModuleDescriptorParserRegistry;
import org.apache.ivy.util.Message;
import org.apache.ivyde.eclipse.workspaceresolver.WorkspaceIvySettings;
import org.apache.ivyde.eclipse.workspaceresolver.WorkspaceResolver;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.BundleContext;

/**
 * This class is a front end to the container configuration. It computes the configuration status,
 * build the Ivy bean on demand and can cache it.
 */
public abstract class CachedIvy {

    private Ivy ivy;

    private long ivySettingsLastModified = -1;

    private ModuleDescriptor md;

    public void reset() {
        md = null;
        ivy = null;
        ivySettingsLastModified = -1;
    }

    public void setIvySettingsLastModified(long ivySettingsLastModified) {
        this.ivySettingsLastModified = ivySettingsLastModified;
    }

    public void setErrorMarker(IvyDEException ex) {
        IvyMarkerManager ivyMarkerManager = IvyPlugin.getDefault().getIvyMarkerManager();
        IStatus status;
        if (ex != null) {
            status = new Status(IStatus.ERROR, IvyPlugin.ID, IStatus.ERROR, ex.getMessage(),
                    ex.getCause());
        } else {
            status = Status.OK_STATUS;
        }
        ivyMarkerManager.setResolveStatus(status, getProject(), getIvyXmlPath());
    }

    protected abstract IProject getProject();

    protected abstract String getIvyXmlPath();

    protected abstract ResolvedPath getIvySettingsPath() throws IvyDEException;

    protected abstract boolean isLoadSettingsOnDemandPath();

    protected abstract Collection getPropertyFiles() throws IvyDEException;

    protected abstract boolean isResolveInWorkspace();

    public Ivy getCachedIvy() {
        if (ivy != null) {
            return ivy;
        }
        try {
            getIvy();
            setErrorMarker(null);
            return ivy;
        } catch (IvyDEException e) {
            setErrorMarker(e);
            return null;
        }
    }

    public Ivy getSafelyIvy() {
        try {
            getIvy();
            setErrorMarker(null);
            return ivy;
        } catch (IvyDEException e) {
            setErrorMarker(e);
            return null;
        }
    }

    public Ivy getIvy() throws IvyDEException {
        try {
            return doGetIvy();
        } catch (IvyDEException e) {
            e.contextualizeMessage("Error while resolving the ivy instance for " + this.toString());
            throw e;
        }
    }

    private Ivy doGetIvy() throws IvyDEException {
        ResolvedPath settingsPath = getIvySettingsPath();
        if (settingsPath.getError() != null) {
            throw new IvyDEException("Incorrect path of the Ivy settings",
                    "The Ivy settings path '" + settingsPath.getInputPath() + "' is incorrect: "
                            + settingsPath.getError().getMessage(), settingsPath.getError());
        }
        if (!settingsPath.isSet()) {
            // no settings specified, so take the default one
            if (ivy == null) {
                IvySettings ivySettings = createIvySettings();
                ivy = Ivy.newInstance(ivySettings);
                try {
                    ivy.configureDefault();
                } catch (ParseException e) {
                    ivy = null;
                    IvyDEException ex = new IvyDEException(
                            "Parsing error of the default Ivy settings",
                            "The default Ivy settings file could not be parsed: " + e.getMessage(),
                            e);
                    throw ex;
                } catch (IOException e) {
                    ivy = null;
                    throw new IvyDEException("Read error of the default Ivy settings",
                            "The default Ivy settings file could not be read: " + e.getMessage(), e);
                }
            }
            return ivy;
        }

        // before returning the found ivy, try to refresh it if the settings changed
        if (settingsPath.getFile() != null) {
            return getIvyFromFile(settingsPath);
        } else {
            // an URL but not a file
            if (ivy == null || ivySettingsLastModified == -1) {
                IvySettings ivySettings = createIvySettings();
                ivy = Ivy.newInstance(ivySettings);
                try {
                    ivy.configure(settingsPath.getUrl());
                    ivySettingsLastModified = 0;
                } catch (ParseException e) {
                    ivy = null;
                    throw new IvyDEException("Parsing error of the Ivy settings",
                            "The ivy settings file '" + settingsPath.getResolvedPath()
                                    + "' could not be parsed: " + e.getMessage(), e);
                } catch (IOException e) {
                    ivy = null;
                    throw new IvyDEException("Read error of the Ivy settings",
                            "The ivy settings file '" + settingsPath.getResolvedPath()
                                    + "' could not be read: " + e.getMessage(), e);
                }
            }
        }
        return ivy;
    }

    private Ivy getIvyFromFile(ResolvedPath ivySettingsPath) throws IvyDEException {
        File file = ivySettingsPath.getFile();
        if (!file.exists()) {
            throw new IvyDEException("Ivy settings file not found", "The Ivy settings file '"
                    + ivySettingsPath.getResolvedPath() + "' cannot be found", null);
        }

        if (file.lastModified() != ivySettingsLastModified || !isLoadSettingsOnDemandPath()) {
            IvySettings ivySettings = createIvySettings();
            if (ivySettingsLastModified == -1) {
                Message.info("\n\n");
            } else {
                Message.info("\n\nIVYDE: ivysettings has changed, configuring ivy again\n");
            }
            ivy = Ivy.newInstance(ivySettings);
            try {
                ivy.configure(file);
            } catch (ParseException e) {
                ivy = null;
                throw new IvyDEException("Parsing error of the Ivy settings",
                        "The ivy settings file '" + ivySettingsPath.getResolvedPath()
                                + "' could not be parsed: " + e.getMessage(), e);
            } catch (IOException e) {
                ivy = null;
                throw new IvyDEException("Read error of the Ivy settings",
                        "The ivy settings file '" + ivySettingsPath.getResolvedPath()
                                + "' could not be read: " + e.getMessage(), e);
            }
            ivySettingsLastModified = file.lastModified();
        }
        return ivy;
    }

    private IvySettings createIvySettings() throws IvyDEException {
        IvySettings ivySettings;
        if (isResolveInWorkspace()) {
            ivySettings = new WorkspaceIvySettings(getProject());
            DefaultRepositoryCacheManager cacheManager = new DefaultRepositoryCacheManager();
            BundleContext bundleContext = IvyPlugin.getDefault().getBundleContext();
            cacheManager.setBasedir(bundleContext.getDataFile("ivyde-workspace-resolver-cache"));
            cacheManager.setCheckmodified(true);
            cacheManager.setUseOrigin(true);
            cacheManager.setName(WorkspaceResolver.CACHE_NAME);
            ivySettings.addRepositoryCacheManager(cacheManager);
        } else {
            ivySettings = new IvySettings();
        }
        if (getProject() != null) {
            IPath location = getProject().getLocation();
            if (location != null) {
                ivySettings.setBaseDir(location.toFile());
            }
        }
        Collection propFiles = getPropertyFiles();
        if (propFiles != null) {
            Iterator iter = propFiles.iterator();
            while (iter.hasNext()) {
                String file = (String) iter.next();
                InputStream is;
                Path p = new Path(file);
                if (getProject() != null && !p.isAbsolute()) {
                    try {
                        is = new FileInputStream(getProject().getLocation().append(file).toFile());
                    } catch (FileNotFoundException e) {
                        throw new IvyDEException("Property file not found", "The property file '"
                                + file + "' could not be found", e);
                    }
                } else {
                    try {
                        is = new FileInputStream(file);
                    } catch (FileNotFoundException e) {
                        throw new IvyDEException("Property file not found", "The property file '"
                                + file + "' was not found", e);
                    }
                }
                Properties props = new Properties();
                try {
                    props.load(is);
                } catch (IOException e) {
                    throw new IvyDEException("Not a property file", "The property file '" + file
                            + "' could not be loaded", e);
                }
                try {
                    is.close();
                } catch (IOException e) {
                    // don't care
                }

                Iterator keys = props.keySet().iterator();
                while (keys.hasNext()) {
                    String key = (String) keys.next();
                    String value = props.getProperty(key);
                    ivySettings.setVariable(key, value);
                }
            }
        }
        return ivySettings;
    }

    public File getIvyFile() throws IvyDEException {
        ResolvedPath ivyPath = new ResolvedPath(getIvyXmlPath(), getProject());
        if (ivyPath.getError() != null) {
            throw new IvyDEException("Incorrect path of the ivy.xml",
                    "The ivy.xml path '" + ivyPath.getInputPath() + "' is incorrect: "
                            + ivyPath.getError().getMessage(), ivyPath.getError());
        }
        if (!ivyPath.isSet()) {
            throw new IvyDEException("Empty path of the ivy.xml",
                    "The ivy.xml path is resolved to be empty: '" + ivyPath.getInputPath() + "'",
                    null);
        }
        if (ivyPath.getFile() == null) {
            throw new IvyDEException("The path of the ivy.xml is not a local file",
                    "The ivy.xml path is resolved to be a file: '" + ivyPath.getResolvedPath()
                            + "'", null);
        }
        return ivyPath.getFile();
    }

    public ModuleDescriptor getCachedModuleDescriptor() {
        if (md != null) {
            return md;
        }
        try {
            if (ivy == null) {
                ivy = getIvy();
            }
            getModuleDescriptor(ivy);
            setErrorMarker(null);
            return md;
        } catch (IvyDEException e) {
            setErrorMarker(e);
            return null;
        }
    }

    public ModuleDescriptor getModuleDescriptor() throws IvyDEException {
        return getModuleDescriptor(getIvy());
    }

    public ModuleDescriptor getCachedModuleDescriptor(Ivy i) {
        if (md != null) {
            return md;
        }
        try {
            getModuleDescriptor(i);
            setErrorMarker(null);
            return md;
        } catch (IvyDEException e) {
            setErrorMarker(e);
            return null;
        }
    }

    public ModuleDescriptor getSafelyModuleDescriptor(Ivy i) {
        try {
            getModuleDescriptor(i);
            setErrorMarker(null);
            return md;
        } catch (IvyDEException e) {
            setErrorMarker(e);
            return null;
        }
    }

    public ModuleDescriptor getModuleDescriptor(Ivy i) throws IvyDEException {
        File file = getIvyFile();
        if (!file.exists()) {
            throw new IvyDEException("Ivy file not found", "The ivy.xml file '"
                    + file.getAbsolutePath() + "' was not found", null);
        }
        try {
            md = ModuleDescriptorParserRegistry.getInstance().parseDescriptor(i.getSettings(),
                file.toURI().toURL(), false);
            return md;
        } catch (MalformedURLException e) {
            throw new IvyDEException("Incorrect URL of the Ivy file",
                    "The URL to the ivy.xml file is incorrect: '" + file.getAbsolutePath() + "'", e);
        } catch (ParseException e) {
            throw new IvyDEException("Parsing error of the Ivy file", "The ivy file '"
                    + file.getAbsolutePath() + "' could not be parsed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IvyDEException("Read error of the Ivy file", "The ivy file '"
                    + file.getAbsolutePath() + "' could not be read: " + e.getMessage(), e);
        }
    }

}
