/*******************************************************************************
 * Copyright (c) 2012 Pivotal Software, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal Software, Inc. - initial API and implementation
 *******************************************************************************/
package org.springsource.ide.eclipse.commons.configurator;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.osgi.util.NLS;
import org.eclipse.ui.dialogs.IOverwriteQuery;
import org.eclipse.wst.server.core.IModule;
import org.eclipse.wst.server.core.IRuntime;
import org.eclipse.wst.server.core.IRuntimeWorkingCopy;
import org.eclipse.wst.server.core.IServer;
import org.eclipse.wst.server.core.IServerListener;
import org.eclipse.wst.server.core.IServerType;
import org.eclipse.wst.server.core.IServerWorkingCopy;
import org.eclipse.wst.server.core.ServerCore;
import org.eclipse.wst.server.core.ServerEvent;
import org.eclipse.wst.server.core.ServerPort;
import org.eclipse.wst.server.core.ServerUtil;
import org.springsource.ide.eclipse.commons.core.Policy;
import org.springsource.ide.eclipse.commons.internal.configurator.Activator;
import org.springsource.ide.eclipse.commons.internal.configurator.server.ServerDescriptor;


/**
 * @author Steffen Pingel
 * @author Christian Dupuis
 * @author Terry Denney
 */
public class ServerHandler {

	public static final IOverwriteQuery ALWAYS_OVERWRITE = new IOverwriteQuery() {
		public String queryOverwrite(String arg0) {
			return IOverwriteQuery.YES;
		}
	};

	public static final IOverwriteQuery NEVER_OVERWRITE = new IOverwriteQuery() {
		public String queryOverwrite(String arg0) {
			return IOverwriteQuery.NO;
		}
	};

	private final String serverType;

	private String runtimeName;

	private String serverName;

	/**
	 * Absolute path to the target directory for the server installation.
	 */
	private String serverPath;

	private String verifyPath;

	private boolean forceCreateRuntime;

	public ServerHandler(ServerDescriptor descriptor) {
		this(descriptor, null);
	}

	public ServerHandler(ServerDescriptor descriptor, File path) {
		this(descriptor.getServerTypeId());
		setRuntimeName(descriptor.getRuntimeName());
		setServerName(descriptor.getServerName());
		setForceCreateRuntime(descriptor.getForceCreateRuntime());
		if (path != null) {
			setServerPath(path.getAbsolutePath());
		}
	}

	public ServerHandler(String serverType) {
		this.serverType = serverType;
		this.verifyPath = "conf";
	}

	public IServer getExistingServer() {
		IProgressMonitor monitor = new NullProgressMonitor();
		try {
			IServerType st = ServerCore.findServerType(serverType);
			if (st == null) {
				return null;
			}
			IRuntime runtime;
			if (serverPath != null || forceCreateRuntime) {
				runtime = ServerCore.findRuntime(runtimeName);
				if (runtime == null) {
					return null;
				}
			}
			else {
				runtime = findRuntime(st, monitor);
			}

			if (serverName != null) {
				return ServerCore.findServer(serverName);
			}
			else {
				return findServer(st, runtime, monitor);
			}
		}
		catch (CoreException e) {
			return null;
		}
	}

	public IServer createServer(IProgressMonitor monitor, IOverwriteQuery query) throws CoreException {
		return createServer(monitor, query, null);
	}

	public IServer createServer(IProgressMonitor monitor, IOverwriteQuery query, ServerHandlerCallback callback)
			throws CoreException {
		try {
			monitor.beginTask("Creating server configuration", 4);

			IServerType st = ServerCore.findServerType(serverType);
			if (st == null) {
				throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Could not find server type \""
						+ serverType + "\""));
			}
			IRuntime runtime;
			if (serverPath != null) {
				runtime = createRuntime(st, new Path(serverPath), monitor, query);
			}
			else if (forceCreateRuntime) {
				runtime = createRuntime(st, null, monitor, query);
			}
			else {
				runtime = findRuntime(st, monitor);
			}

			if (serverName != null) {
				return createServer(st, runtime, monitor, query, callback);
			}
			else {
				return findServer(st, runtime, monitor);
			}
		}
		finally {
			monitor.done();
		}
	}

	public void deleteServerAndRuntime(IProgressMonitor monitor) throws CoreException {
		try {
			monitor.beginTask("Deleting server configuration", 4);

			IServer server = ServerCore.findServer(serverName);
			if (server != null) {
				IFolder serverConfiguration = server.getServerConfiguration();
				server.delete();
				if (serverConfiguration != null) {
					serverConfiguration.delete(true, true, monitor);
				}
			}

			IRuntime runtime = ServerCore.findRuntime(runtimeName);
			if (runtime != null) {
				runtime.delete();
			}
		}
		finally {
			monitor.done();
		}
	}

	public int getHttpPort(IServer server, IProgressMonitor monitor) {
		ServerPort[] ports = server.getServerPorts(monitor);
		if (ports != null) {
			for (ServerPort serverPort : ports) {
				if ("http".equals(serverPort.getProtocol())) {
					return serverPort.getPort();
				}
			}
		}
		return -1;
	}

	public String getRuntimeName() {
		return runtimeName;
	}

	public String getVerifyPath() {
		return verifyPath;
	}

	public IServer launch(IProject project, IProgressMonitor monitor) throws CoreException {
		try {
			monitor.beginTask("Launching " + project.getName(), IProgressMonitor.UNKNOWN);

			IServer server = createServer(new SubProgressMonitor(monitor, 1), NEVER_OVERWRITE);

			IServerWorkingCopy wc = server.createWorkingCopy();
			IModule[] modules = ServerUtil.getModules(project);
			if (modules == null || modules.length == 0) {
				throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID,
						"Sample project does not contain web modules: " + project));
			}

			if (!Arrays.asList(wc.getModules()).contains(modules[0])) {
				wc.modifyModules(modules, new IModule[0], monitor);
				server = wc.save(true, monitor);
			}
			server.publish(IServer.PUBLISH_INCREMENTAL, monitor);

			restartServer(server, monitor);

			return server;
		}
		finally {
			monitor.done();
		}
	}

	public void setForceCreateRuntime(boolean forceCreateRuntime) {
		this.forceCreateRuntime = forceCreateRuntime;
	}

	public void setRuntimeName(String runtimeName) {
		this.runtimeName = runtimeName;
	}

	public void setServerName(String serverName) {
		this.serverName = serverName;
	}

	public void setServerPath(String serverPath) {
		this.serverPath = serverPath;
	}

	public void setVerifyPath(String verifyPath) {
		this.verifyPath = verifyPath;
	}

	public String getServerName() {
		return serverName;
	}

	public String getServerPath() {
		return serverPath;
	}

	private IRuntime createRuntime(IServerType st, IPath path, IProgressMonitor monitor, IOverwriteQuery query)
			throws CoreException {
		IRuntime runtime = ServerCore.findRuntime(runtimeName);
		if (runtime != null) {
			if (!query(query, NLS.bind("A runtime with the name ''{0}'' already exists. Replace the existing runtime?",
					runtimeName))) {
				monitor.worked(1);
				return runtime;
			}
			else {
				runtime.delete();
			}
		}
		IRuntimeWorkingCopy wc = st.getRuntimeType().createRuntime(runtimeName, new SubProgressMonitor(monitor, 1));
		wc.setName(runtimeName);

		if (path != null) {
			wc.setLocation(path);
		}

		IStatus validationResult = wc.validate(monitor);
		if (!validationResult.isOK()) {
			throw new CoreException(validationResult);
		}

		runtime = wc.save(true, new SubProgressMonitor(monitor, 1));

		return runtime;
	}

	private IServer createServer(IServerType st, IRuntime runtime, IProgressMonitor monitor, IOverwriteQuery query,
			ServerHandlerCallback callback) throws CoreException {
		IServer server = ServerCore.findServer(serverName);
		if (server != null) {
			if (!query(query,
					NLS.bind("A server with the name ''{0}'' already exists. Replace the existing server?", serverName))) {
				monitor.worked(1);
				return server;
			}
			else {
				IFolder serverConfiguration = server.getServerConfiguration();
				server.delete();
				if (serverConfiguration != null) {
					serverConfiguration.delete(true, true, monitor);
				}
			}
		}

		IServerWorkingCopy wc = st.createServer(serverName, null, runtime, new SubProgressMonitor(monitor, 1));
		wc.setName(serverName);
		if (callback != null) {
			callback.configureServer(wc);
		}
		server = wc.save(true, new SubProgressMonitor(monitor, 1));
		return server;
	}

	private IRuntime findRuntime(IServerType st, IProgressMonitor monitor) throws CoreException {
		IRuntime[] runtimes = ServerCore.getRuntimes();
		if (runtimes != null) {
			for (IRuntime runtime : runtimes) {
				if (runtime.getRuntimeType().equals(st.getRuntimeType())) {
					return runtime;
				}
			}
		}
		throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID, "No matching runtime found"));
	}

	private IServer findServer(IServerType st, IRuntime runtime, IProgressMonitor monitor) throws CoreException {
		IServer[] servers = ServerCore.getServers();
		if (servers != null) {
			for (IServer server : servers) {
				if (server.getRuntime().getRuntimeType().equals(st.getRuntimeType())) {
					return server;
				}
			}
		}
		throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID, "No matching server found"));
	}

	private boolean query(IOverwriteQuery query, String message) {
		String response = query.queryOverwrite(message);
		if (IOverwriteQuery.CANCEL.equals(response)) {
			throw new OperationCanceledException();
		}
		if (IOverwriteQuery.YES.equals(response)) {
			return true;
		}
		return false;
	}

	private void restartServer(IServer server, IProgressMonitor monitor) throws CoreException {
		monitor.subTask("Restarting server");

		final CountDownLatch eventLatch = new CountDownLatch(1);
		IServerListener serverListener = new IServerListener() {
			public void serverChanged(ServerEvent event) {
				if (event.getState() == IServer.STATE_STARTED) {
					eventLatch.countDown();
				}
			}
		};

		try {
			server.addServerListener(serverListener);

			if (server.getServerState() != IServer.STATE_STARTED) {
				server.start(ILaunchManager.DEBUG_MODE, monitor);
			}
			else if (server.getServerRestartState()) {
				server.restart(ILaunchManager.DEBUG_MODE, monitor);
			}
			else {
				return;
			}

			// wait 10 seconds
			monitor.subTask("Waiting for server to start");
			for (int i = 0; i < 50; i++) {
				try {
					if (eventLatch.await(200, TimeUnit.MILLISECONDS)) {
						break;
					}
				}
				catch (InterruptedException e) {
					throw new OperationCanceledException();
				}
				Policy.checkCancelled(monitor);
			}
		}
		finally {
			server.removeServerListener(serverListener);
		}
	}

}
