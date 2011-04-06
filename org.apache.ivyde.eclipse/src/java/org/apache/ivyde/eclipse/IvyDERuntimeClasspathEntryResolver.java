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

import java.util.ArrayList;
import java.util.List;

import org.apache.ivyde.eclipse.cpcontainer.ClasspathEntriesResolver;
import org.apache.ivyde.eclipse.cpcontainer.IvyClasspathContainer;
import org.apache.ivyde.eclipse.resolve.IvyResolveJob;
import org.apache.ivyde.eclipse.resolve.ResolveRequest;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jdt.launching.IRuntimeClasspathEntry;
import org.eclipse.jdt.launching.IRuntimeClasspathEntry2;
import org.eclipse.jdt.launching.IRuntimeClasspathEntryResolver;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;

/**
 * Resolver that doesn't include the non exported library of the imported project in the IvyDE
 * container, contrary to the default behavior.
 * <p>
 * See also https://bugs.eclipse.org/bugs/show_bug.cgi?id=284150
 */
public class IvyDERuntimeClasspathEntryResolver implements IRuntimeClasspathEntryResolver {

    public IRuntimeClasspathEntry[] resolveRuntimeClasspathEntry(IRuntimeClasspathEntry entry,
            ILaunchConfiguration configuration) throws CoreException {
        if (entry == null) {
            // cannot resolve without entry or project context
            return new IRuntimeClasspathEntry[0];
        }

        IvyClasspathContainer ivycp;

        IJavaProject project = entry.getJavaProject();
        if (project == null) {
            ivycp = new IvyClasspathContainer(null, entry.getPath(), null, null);
        } else {
            IClasspathContainer container = JavaCore
                    .getClasspathContainer(entry.getPath(), project);
            if (container == null) {
                String message = "Could not resolve classpath container: "
                        + entry.getPath().toString();
                throw new CoreException(new Status(IStatus.ERROR, IvyPlugin.ID,
                        IJavaLaunchConfigurationConstants.ERR_INTERNAL_ERROR, message, null));
                // execution will not reach here - exception will be thrown
            }
            ivycp = (IvyClasspathContainer) container;
        }

        return computeDefaultContainerEntries(ivycp, entry);
    }

    private static IRuntimeClasspathEntry[] computeDefaultContainerEntries(
            IvyClasspathContainer ivycp, IRuntimeClasspathEntry entry) throws CoreException {
        IClasspathEntry[] cpes;
        if (ivycp.getClasspathEntries() == null || ivycp.getConf().isInheritedResolveBeforeLaunch()) {
            ClasspathEntriesResolver resolver = new ClasspathEntriesResolver(ivycp, false);
            ResolveRequest request = new ResolveRequest(resolver, ivycp.getState());
            request.setForceFailOnError(true);
            request.setInWorkspace(ivycp.getConf().isInheritedResolveInWorkspace());
            IvyResolveJob resolveJob = IvyPlugin.getDefault().getIvyResolveJob();
            IStatus status = resolveJob.launchRequest(request, new NullProgressMonitor());
            if (status.getCode() != IStatus.OK) {
                throw new CoreException(status);
            }
            cpes = resolver.getClasspathEntries();
        } else {
            cpes = ivycp.getClasspathEntries();
        }
        List resolved = new ArrayList(cpes.length);
        List projects = new ArrayList();
        for (int i = 0; i < cpes.length; i++) {
            IClasspathEntry cpe = cpes[i];
            if (cpe.getEntryKind() == IClasspathEntry.CPE_PROJECT) {
                IProject p = ResourcesPlugin.getWorkspace().getRoot()
                        .getProject(cpe.getPath().segment(0));
                IJavaProject jp = JavaCore.create(p);
                if (!projects.contains(jp)) {
                    projects.add(jp);
                    IRuntimeClasspathEntry classpath = JavaRuntime
                            .newProjectRuntimeClasspathEntry(jp);
                    resolved.add(classpath);
                    IRuntimeClasspathEntry[] entries = JavaRuntime.resolveRuntimeClasspathEntry(
                        classpath, jp);
                    for (int j = 0; j < entries.length; j++) {
                        IRuntimeClasspathEntry e = entries[j];
                        if (!resolved.contains(e)) {
                            resolved.add(entries[j]);
                        }
                    }
                }
            } else if (cpe.getEntryKind() == IClasspathEntry.CPE_LIBRARY) {
                IRuntimeClasspathEntry e = JavaRuntime.newArchiveRuntimeClasspathEntry(cpe
                        .getPath());
                if (!resolved.contains(e)) {
                    resolved.add(e);
                }
            }
        }
        // set classpath property
        IRuntimeClasspathEntry[] result = new IRuntimeClasspathEntry[resolved.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = (IRuntimeClasspathEntry) resolved.get(i);
            result[i].setClasspathProperty(IRuntimeClasspathEntry.USER_CLASSES);
        }
        return result;
    }

    public IRuntimeClasspathEntry[] resolveRuntimeClasspathEntry(IRuntimeClasspathEntry entry,
            IJavaProject project) throws CoreException {
        if (!(entry instanceof IRuntimeClasspathEntry2)) {
            return new IRuntimeClasspathEntry[] {entry};
        }

        IRuntimeClasspathEntry2 entry2 = (IRuntimeClasspathEntry2) entry;
        IRuntimeClasspathEntry[] entries = entry2.getRuntimeClasspathEntries(null);
        List resolved = new ArrayList();
        for (int i = 0; i < entries.length; i++) {
            IRuntimeClasspathEntry[] temp = JavaRuntime.resolveRuntimeClasspathEntry(entries[i],
                project);
            for (int j = 0; j < temp.length; j++) {
                resolved.add(temp[j]);
            }
        }
        return (IRuntimeClasspathEntry[]) resolved.toArray(new IRuntimeClasspathEntry[resolved
                .size()]);
    }

    public IVMInstall resolveVMInstall(IClasspathEntry entry) throws CoreException {
        return null;
    }

}
