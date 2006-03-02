/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.jdt.debug.tests.performance;

import org.eclipse.core.resources.IMarkerDelta;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointListener;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.internal.ui.DebugUIPlugin;
import org.eclipse.jdt.debug.core.IJavaLineBreakpoint;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jdt.debug.core.IJavaWatchpoint;
import org.eclipse.jdt.debug.core.JDIDebugModel;
import org.eclipse.jdt.debug.tests.AbstractDebugPerformanceTest;
import org.eclipse.jdt.internal.debug.core.refactoring.DeleteBreakpointChange;
import org.eclipse.test.performance.Dimension;

public class PerfBreakpointTests extends AbstractDebugPerformanceTest implements IBreakpointListener {

    int breakpointCount = 0;

    public PerfBreakpointTests(String name) {
        super(name);
    }

    public void testLineBreakpointCreation() throws Exception {
        tagAsSummary("Install Line Breakpoints", Dimension.ELAPSED_PROCESS);
        String typeName = "LargeSourceFile";
        IResource resource = getBreakpointResource(typeName);

        IJavaLineBreakpoint bp = createLineBreakpoint(14, typeName);
        IJavaThread thread = launchToBreakpoint(typeName, false);
        bp.delete();
        
        try {
            DebugPlugin.getDefault().getBreakpointManager().addBreakpointListener(this);

            int[] lineNumbers = new int[150];
            for (int i = 0; i < lineNumbers.length; i++) {
                lineNumbers[i] = 15 + i;
            }
            
            for (int i = 0; i < 10; i++) {
                createLineBreakpoints(resource, typeName, lineNumbers);
                waitForBreakpointCount(lineNumbers.length);
                removeAllBreakpoints();
                deleteAllBreakpoints();
                waitForBreakpointCount(0);
                breakpointCount = 0;  
            }

            for (int i = 0; i < 100; i++) {
                System.gc();
                startMeasuring();
                createLineBreakpoints(resource, typeName, lineNumbers);
                waitForBreakpointCount(lineNumbers.length);
                stopMeasuring();
                removeAllBreakpoints();
                deleteAllBreakpoints();
                waitForBreakpointCount(0);
                breakpointCount = 0;
            }
            commitMeasurements();
            assertPerformance();
        } finally {
            DebugPlugin.getDefault().getBreakpointManager().removeBreakpointListener(this);
            removeAllBreakpoints();
            deleteAllBreakpoints();
            terminateAndRemove(thread);
        }
    }

    public void testBreakpointRemoval() throws Exception {
        tagAsSummary("Remove Line Breakpoints", Dimension.ELAPSED_PROCESS);
        String typeName = "LargeSourceFile";
        IResource resource = getBreakpointResource(typeName);

        IJavaLineBreakpoint bp = createLineBreakpoint(14, typeName);
        IJavaThread thread = launchToBreakpoint(typeName, false);
        bp.delete();

        IBreakpointManager manager = DebugPlugin.getDefault().getBreakpointManager();
        try {
            manager.addBreakpointListener(this);

            int[] lineNumbers = new int[50];
            for (int i = 0; i < lineNumbers.length; i++) {
                lineNumbers[i] = 15 + i;
            }

            for (int i = 0; i < 10; i++) {
                createLineBreakpoints(resource, typeName, lineNumbers);
                waitForBreakpointCount(lineNumbers.length);
                IBreakpoint[] breakpoints = manager.getBreakpoints();
                manager.removeBreakpoints(breakpoints, true);
                waitForBreakpointCount(0);
                deleteAllBreakpoints();
            }

            lineNumbers = new int[250];
            for (int i = 0; i < lineNumbers.length; i++) {
                lineNumbers[i] = 15 + i;
            }

            for (int i = 0; i < 150; i++) {
                createLineBreakpoints(resource, typeName, lineNumbers);
                waitForBreakpointCount(lineNumbers.length);
                IBreakpoint[] breakpoints = manager.getBreakpoints();
                System.gc();
                startMeasuring();
                manager.removeBreakpoints(breakpoints, true);
                waitForBreakpointCount(0);
                stopMeasuring();
                deleteAllBreakpoints();
            }
            commitMeasurements();
            assertPerformance();
        } finally {
            manager.removeBreakpointListener(this);
            removeAllBreakpoints();
            deleteAllBreakpoints();
            terminateAndRemove(thread);
        }
    }
    


    private void deleteAllBreakpoints() {
    	IBreakpoint[] breakpoints = DebugPlugin.getDefault().getBreakpointManager().getBreakpoints();
		for (int i = 0; i < breakpoints.length; i++) {
			IBreakpoint breakpoint = breakpoints[i];
			try {
				breakpoint.delete();
			} catch (CoreException e) {
				DebugPlugin.log(e);
			}
		}
	}

    public void testMethodEntryBreakpointCreation() throws Exception {
        tagAsSummary("Install Method Entry Breakpoints", Dimension.ELAPSED_PROCESS);
        String typeName = "LargeSourceFile";
        IProject project = getJavaProject().getProject();
        
        IJavaLineBreakpoint bp = createLineBreakpoint(14, typeName);
        IJavaThread thread = launchToBreakpoint(typeName, false);
        bp.delete();

        IBreakpointManager manager = DebugPlugin.getDefault().getBreakpointManager();
        try {
            manager.addBreakpointListener(this);

            String[] methods = new String[300];
            for (int i = 0; i < methods.length; i++) {
                methods[i] = "method"+(i+1);
            }

            for (int i = 0; i < 10; i++) {
                createMethodEntryBreakpoints(project, typeName, methods);
                waitForBreakpointCount(methods.length);
                removeAllBreakpoints();
                waitForBreakpointCount(0);
                deleteAllBreakpoints();
            }

            for (int i = 0; i < 100; i++) {
                System.gc();
                startMeasuring();
                createMethodEntryBreakpoints(project, typeName, methods);
                waitForBreakpointCount(methods.length);
                stopMeasuring();
                removeAllBreakpoints();
                deleteAllBreakpoints();
                breakpointCount = 0;
            }
            commitMeasurements();
            assertPerformance();
        } finally {
            DebugPlugin.getDefault().getBreakpointManager().removeBreakpointListener(this);
            removeAllBreakpoints();
            deleteAllBreakpoints();
            terminateAndRemove(thread);
        }        
    }

    public void testWatchpointCreation() throws Exception {
        tagAsSummary("Install Watchpoints", Dimension.ELAPSED_PROCESS);
        String typeName = "LotsOfFields";
        IResource resource = getBreakpointResource(typeName);
        
        IJavaLineBreakpoint bp = createLineBreakpoint(516, typeName);
        IJavaThread thread = launchToBreakpoint(typeName, false);
        bp.delete();

        IBreakpointManager manager = DebugPlugin.getDefault().getBreakpointManager();
        try {
            manager.addBreakpointListener(this);

            String[] fields = new String[300];
            for (int i = 0; i < fields.length; i++) {
                fields[i] = "field_"+(i+1);
            }

            for (int i = 0; i < 10; i++) {
                createWatchpoints(resource, typeName, fields);
                waitForBreakpointCount(fields.length);
                removeAllBreakpoints();
                waitForBreakpointCount(0);
                deleteAllBreakpoints();
            }

            for (int i = 0; i < 100; i++) {
                System.gc();
                startMeasuring();
                createWatchpoints(resource, typeName, fields);
                waitForBreakpointCount(fields.length);
                stopMeasuring();
                removeAllBreakpoints();
                deleteAllBreakpoints();
                breakpointCount = 0;
            }
            commitMeasurements();
            assertPerformance();
        } finally {
            DebugPlugin.getDefault().getBreakpointManager().removeBreakpointListener(this);
            removeAllBreakpoints();
            deleteAllBreakpoints();
            terminateAndRemove(thread);
        }        
    }
    
    private synchronized void waitForBreakpointCount(int i) throws Exception {
        long end = System.currentTimeMillis() + 60000;
        while (breakpointCount != i && System.currentTimeMillis() < end) {
            wait(30000);
        }
        assertEquals("Expected " + i + " breakpoints, notified of " + breakpointCount, i, breakpointCount);
    }

    private void createLineBreakpoints(IResource resource, String typeName, int[] lineNumbers) throws CoreException {
        for (int i = 0; i < lineNumbers.length; i++) {
            JDIDebugModel.createLineBreakpoint(resource, typeName, lineNumbers[i], -1, -1, 0, true, null);
        }
    }

    private void createMethodEntryBreakpoints(IProject project, String typeName, String[] methods) throws CoreException {
        for (int i = 0; i < methods.length; i++) {
            String methodName = methods[i];
            JDIDebugModel.createMethodBreakpoint(project, typeName, methodName, "()V", true, false, false, -1, -1, -1, 0, true, null);
        }
    }
    
    private void createWatchpoints(IResource resource, String typeName, String[] fields) throws Exception {
        for(int i = 0; i < fields.length; i++) {
            IJavaWatchpoint wp = JDIDebugModel.createWatchpoint(resource, typeName, fields[i], -1, -1, -1, 0, true, null);
            wp.setAccess(true);
            wp.setModification(true);
        }
    }
    
    public synchronized void breakpointAdded(IBreakpoint breakpoint) {
        breakpointCount++;
        notifyAll();
    }

    public synchronized void breakpointRemoved(IBreakpoint breakpoint, IMarkerDelta delta) {
        breakpointCount--;
        notifyAll();
    }

    public void breakpointChanged(IBreakpoint breakpoint, IMarkerDelta delta) {
    }
}
