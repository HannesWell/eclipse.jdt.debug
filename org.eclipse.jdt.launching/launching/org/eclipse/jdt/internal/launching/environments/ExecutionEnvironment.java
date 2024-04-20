/*******************************************************************************
 *  Copyright (c) 2005, 2022 IBM Corporation and others.
 *
 *  This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License 2.0
 *  which accompanies this distribution, and is available at
 *  https://www.eclipse.org/legal/epl-2.0/
 *
 *  SPDX-License-Identifier: EPL-2.0
 *
 *  Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.launching.environments;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.IntStream;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.IAccessRule;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaModel;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.launching.LaunchingPlugin;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.IVMInstallChangedListener;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.LibraryLocation;
import org.eclipse.jdt.launching.PropertyChangeEvent;
import org.eclipse.jdt.launching.environments.IAccessRuleParticipant;
import org.eclipse.jdt.launching.environments.IExecutionEnvironment;
import org.eclipse.osgi.util.NLS;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.Version;

/**
 * A contributed execution environment.
 *
 * @since 3.2
 */
class ExecutionEnvironment implements IExecutionEnvironment {

	/**
	 * Add a VM changed listener to clear cached values when a VM changes or is removed
	 */
	private final IVMInstallChangedListener fListener = new IVMInstallChangedListener() {

		@Override
		public void defaultVMInstallChanged(IVMInstall previous, IVMInstall current) {}

		@Override
		public void vmAdded(IVMInstall newVm) {}

		@Override
		public void vmChanged(PropertyChangeEvent event) {
			if (event.getSource() != null) {
				fParticipantMap.remove(event.getSource());
				fRuleCache.remove(event.getSource());
			}
		}

		@Override
		public void vmRemoved(IVMInstall removedVm) {
			fParticipantMap.remove(removedVm);
			fRuleCache.remove(removedVm);
		}
	};


	/**
	 * The backing <code>IConfigurationElement</code>
	 */
	private final IConfigurationElement fElement;

	/**
	 * Environment specific rule participant or <code>null</code> if none.
	 */
	private IAccessRuleParticipant fRuleParticipant;

	/**
	 * OSGi profile properties or <code>null</code> if none.
	 */
	private Properties fProfileProperties;

	/**
	 * Whether profile properties have been initialized
	 */
	private boolean fPropertiesInitialized;

	/**
	 * Set of compatible vm's - just the strictly compatible ones
	 */
	private final Set<IVMInstall> fStrictlyCompatible = new HashSet<>();

	/**
	 * All compatible vm's
	 */
	private final List<IVMInstall> fCompatibleVMs = new ArrayList<>();

	/**
	 * default VM install or <code>null</code> if none
	 */
	private IVMInstall fDefault = null;

	/**
	 * Cache of access rule participants to consider for this environment.
	 */
	private IAccessRuleParticipant[] fParticipants = null;

	/**
	 * Map of {IVMInstall -> Map of {participant -> IAccessRule[][]}}.
	 * Caches access rules returned by each participant for a given VM.
	 * @since 3.3
	 */
	private final Map<IVMInstall, Map<IAccessRuleParticipant, IAccessRule[][]>> fParticipantMap = new HashMap<>();

	/**
	 * Cache of VM -> IAccessRule[][] based on the current state of the participant
	 * map. These are the union of the latest rules generated by the participants
	 * for a specific VM.
	 * @since 3.3
	 */
	private final Map<IVMInstall, IAccessRule[][]> fRuleCache = new HashMap<>();

	/**
	 * Wild card pattern matching all files
	 */
	static final IPath ALL_PATTERN = IPath.fromOSString("**/*"); //$NON-NLS-1$

	/**
	 * Prefix of compiler settings in properties file
	 */
	private static final String COMPILER_SETTING_PREFIX = JavaCore.PLUGIN_ID + ".compiler"; //$NON-NLS-1$

	/**
	 * Constructor
	 * @param element the backing {@link IConfigurationElement}
	 */
	ExecutionEnvironment(IConfigurationElement element) {
		fElement = element;
		fPropertiesInitialized = false;
		String attribute = fElement.getAttribute(EnvironmentsManager.RULE_PARTICIPANT_ELEMENT);
		if (attribute != null) {
			fRuleParticipant = new AccessRuleParticipant(fElement);
		}
		JavaRuntime.addVMInstallChangedListener(fListener);
	}

	/**
	 * Initializes the <code>EnvironmentsManager</code>
	 */
	private void init() {
		EnvironmentsManager.getDefault().initializeCompatibilities();
	}

	@Override
	public String getId() {
		return fElement.getAttribute("id"); //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return fElement.getAttribute("description"); //$NON-NLS-1$
	}

	@Override
	public IVMInstall[] getCompatibleVMs() {
		init();
		return fCompatibleVMs.toArray(new IVMInstall[fCompatibleVMs.size()]);
	}

	@Override
	public boolean isStrictlyCompatible(IVMInstall vm) {
		init();
		return fStrictlyCompatible.contains(vm);
	}

	@Override
	public IVMInstall getDefaultVM() {
		init();
		return fDefault;
	}

	@Override
	public void setDefaultVM(IVMInstall vm) {
		init();
		if (vm != null && !fCompatibleVMs.contains(vm)) {
			throw new IllegalArgumentException(NLS.bind(EnvironmentMessages.EnvironmentsManager_0, getId()));
		}
		if (vm != null && vm.equals(fDefault)) {
			return;
		}
		fDefault = vm;
		EnvironmentsManager.getDefault().updateDefaultVMs();
		// update classpath containers
		rebindClasspathContainers();
	}

	/**
	 * Updates Java projects referencing this environment, if any.
	 */
	private void rebindClasspathContainers() {
		IJavaModel model = JavaCore.create(ResourcesPlugin.getWorkspace().getRoot());
		if (model != null) {
			try {
				List<IJavaProject> updates = new ArrayList<>();
				IJavaProject[] javaProjects = model.getJavaProjects();
				IPath path = JavaRuntime.newJREContainerPath(this);
				for (IJavaProject project : javaProjects) {
					IClasspathEntry[] rawClasspath = project.getRawClasspath();
					for (IClasspathEntry entry : rawClasspath) {
						if (entry.getEntryKind() == IClasspathEntry.CPE_CONTAINER && entry.getPath().equals(path)) {
							updates.add(project);
						}
					}
				}
				if (!updates.isEmpty()) {
					JavaCore.setClasspathContainer(path,
							updates.toArray(new IJavaProject[updates.size()]),
							new IClasspathContainer[updates.size()],
							new NullProgressMonitor());
				}
			} catch (JavaModelException e) {
				LaunchingPlugin.log(e);
			}
		}
	}

	/**
	 * Adds the specified VM to the listing of compatible VMs, also
	 * adds the VM to the listing of strictly compatible ones based on
	 * the strictlyCompatible flag
	 * @param vm the VM to add to the environment
	 * @param strictlyCompatible if it is strictly compatible
	 */
	void add(IVMInstall vm, boolean strictlyCompatible) {
		if (fCompatibleVMs.contains(vm)) {
			return;
		}
		fCompatibleVMs.add(vm);
		if (strictlyCompatible) {
			fStrictlyCompatible.add(vm);
		}
	}

	/**
	 * Removes the specified VM from the listings of VMs
	 * @param vm the VM to remove
	 */
	void remove(IVMInstall vm) {
		fCompatibleVMs.remove(vm);
		fStrictlyCompatible.remove(vm);
	}

	/**
	 * Sets the default VM to be the one specified
	 * @param vm the VM to set as the default
	 */
	void initDefaultVM(IVMInstall vm) {
		fDefault = vm;
	}

	@Override
	public IAccessRule[][] getAccessRules(IVMInstall vm, LibraryLocation[] libraries, IJavaProject project) {
		IAccessRuleParticipant[] participants = getParticipants();
		Map<IAccessRuleParticipant, IAccessRule[][]> rulesByParticipant = collectRulesByParticipant(participants, vm, libraries, project);
		synchronized (this) {
			Map<IAccessRuleParticipant, IAccessRule[][]> cachedRules = fParticipantMap.get(vm);
			if (cachedRules == null || !cachedRules.equals(rulesByParticipant)) {
				List<List<IAccessRule>> libLists = IntStream.range(0, libraries.length) // array of lists of access rules
						.<List<IAccessRule>> mapToObj(i -> new ArrayList<>()).toList();

				for (IAccessRuleParticipant participant : participants) {
					addRules(rulesByParticipant.get(participant), libLists);
				}
				IAccessRule[][] allRules = libLists.stream().map(l -> l.toArray(IAccessRule[]::new)).toArray(IAccessRule[][]::new);
				fParticipantMap.put(vm, rulesByParticipant);
				fRuleCache.put(vm, allRules);
				return allRules;
			}
			return fRuleCache.get(vm);
		}
	}

	/**
	 * Returns all access rule participants to consider for this environment.
	 * Includes any participant contributed with this environment and all other
	 * stand alone participants.
	 *
	 * @return access rule participants to consider for this environment
	 */
	private synchronized IAccessRuleParticipant[] getParticipants() {
		if (fParticipants == null) {
			// check participants first
			IAccessRuleParticipant[] participants = EnvironmentsManager.getDefault().getAccessRuleParticipants();
			if (fRuleParticipant != null) {
				// ensure environment specific provider is last and not duplicated
				Set<IAccessRuleParticipant> set = new LinkedHashSet<>();
				Collections.addAll(set, participants);
				// remove, add to make last
				set.remove(fRuleParticipant);
				set.add(fRuleParticipant);
				participants = set.toArray(new IAccessRuleParticipant[set.size()]);
			}
			fParticipants = participants;
		}
		return fParticipants;
	}

	/**
	 * Returns a map of participant to the access rules for that participant for the given
	 * VM, libraries, and project.
	 *
	 * @param participants access rule participants
	 * @param vm the VM
	 * @param libraries the {@link LibraryLocation}s
	 * @param project the {@link IJavaProject} context
	 * @return the mapping of {@link IAccessRuleParticipant} to {@link IAccessRule}s
	 */
	private Map<IAccessRuleParticipant, IAccessRule[][]> collectRulesByParticipant(IAccessRuleParticipant[] participants, IVMInstall vm, LibraryLocation[] libraries, IJavaProject project) {
		Map<IAccessRuleParticipant, IAccessRule[][]> map = new HashMap<>();
		for (IAccessRuleParticipant participant : participants) {
			// TODO: use safe runnable
			map.put(participant, participant.getAccessRules(this, vm, libraries, project));
		}
		return map;
	}

	/**
	 * Adds the access rules to each list in the given collection. If the last rule in a
	 * given collection is the wild card pattern then no more rules are added to that collection.
	 *
	 * @param accessRules the list of {@link IAccessRule}s
	 * @param collect the array of lists to collect the {@link IAccessRule}s in
	 */
	private void addRules(IAccessRule[][] accessRules, List<List<IAccessRule>> collect) {
		for (int i = 0; i < accessRules.length; i++) {
			List<IAccessRule> list = collect.get(i);
			// if the last rule is a **/* pattern, don't add any more rules, as they will have no effect
			if (!list.isEmpty()) {
				IAccessRule lastRule = list.get(list.size() - 1);
				if(lastRule.getPattern().equals(ALL_PATTERN)) {
					continue;
				}
			}
			Collections.addAll(list, accessRules[i]);
		}
	}

	@Override
	public Properties getProfileProperties() {
		if (!fPropertiesInitialized) {
			fPropertiesInitialized = true;
			String path = fElement.getAttribute("profileProperties"); //$NON-NLS-1$
			Bundle bundle = null;
			if (path == null) {
				// attempt default profiles known to OSGi
				bundle = Platform.getBundle("org.eclipse.osgi"); //$NON-NLS-1$
				path = getId().replace('/', '_') + ".profile"; //$NON-NLS-1$
			} else {
				// read provided file
				bundle = Platform.getBundle(fElement.getContributor().getName());
			}
			if (bundle != null) {
				fProfileProperties = getJavaProfileProperties(bundle, path);
			}
		}
		return fProfileProperties;
	}

	/**
	 * Returns properties file contained in the specified bundle at the given
	 * bundle relative path, or <code>null</code> if none.
	 *
	 * @param bundle bundle to locate file in
	 * @param path bundle relative path to properties file
	 * @return properties or <code>null</code> if none
	 */
	@SuppressWarnings("deprecation")
	private Properties getJavaProfileProperties(Bundle bundle, String path) {
		Properties profile = new Properties();
		URL profileURL = bundle.getEntry(path);
		if (profileURL != null) {
			try (InputStream is = profileURL.openStream()) {
				if (is != null) {
					profile.load(is);
					fixJavaSE9ComplianceSourceTargetLevels(profile);
				}
			} catch (IOException e) {
				return null;
			}
		} else {
			String compliance = getCompliance();
			if (compliance == null) {
				return null;
			}
			profile.setProperty(JavaCore.COMPILER_COMPLIANCE, compliance);
			profile.setProperty(JavaCore.COMPILER_SOURCE, compliance);
			profile.setProperty(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, compliance);
			profile.setProperty(JavaCore.COMPILER_PB_ASSERT_IDENTIFIER, JavaCore.ERROR);
			profile.setProperty(JavaCore.COMPILER_PB_ENUM_IDENTIFIER, JavaCore.ERROR);
			profile.setProperty(Constants.FRAMEWORK_EXECUTIONENVIRONMENT, calculateVMExecutionEnvs(new Version(compliance)));
			profile.setProperty(JavaCore.COMPILER_RELEASE, JavaCore.ENABLED);
			if (JavaCore.compareJavaVersions(compliance, JavaCore.VERSION_10) > 0) {
				profile.setProperty(JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES, JavaCore.DISABLED);
				profile.setProperty(JavaCore.COMPILER_PB_REPORT_PREVIEW_FEATURES, JavaCore.WARNING);
			}

		}
		// For below Java 9 EE, compiler release option should be disabled by default
		String property = profile.getProperty(JavaCore.COMPILER_COMPLIANCE);
		if (property != null && JavaCore.compareJavaVersions(property, JavaCore.VERSION_9) < 0) {
			profile.setProperty(JavaCore.COMPILER_RELEASE, JavaCore.DISABLED);
		}
		return profile;
	}

	private static final String JAVASE = "JavaSE"; //$NON-NLS-1$
	private static final List<String> PRE_JAVA9_OSGI_EES = List.of( //
			"OSGi/Minimum-1.0", //$NON-NLS-1$
			"OSGi/Minimum-1.1", //$NON-NLS-1$
			"OSGi/Minimum-1.2", //$NON-NLS-1$
			"JavaSE/compact2-1.8", //$NON-NLS-1$
			"JavaSE/compact3-1.8", //$NON-NLS-1$
			"JRE-1.1", //$NON-NLS-1$
			"J2SE-1.2", //$NON-NLS-1$
			"J2SE-1.3", //$NON-NLS-1$
			"J2SE-1.4", //$NON-NLS-1$
			"J2SE-1.5", //$NON-NLS-1$
			"JavaSE-1.6", //$NON-NLS-1$
			"JavaSE-1.7", //$NON-NLS-1$
			"JavaSE-1.8"); //$NON-NLS-1$

	private String calculateVMExecutionEnvs(Version javaVersion) {
		List<String> environments = new ArrayList<>(PRE_JAVA9_OSGI_EES);
		int targetMajor = javaVersion.getMajor();
		for (int major = 9; major <= targetMajor; major++) {
			environments.add(JAVASE + '-' + major);
		}
		for (int minor = 1; minor <= javaVersion.getMinor(); minor++) {
			environments.add(JAVASE + '-' + targetMajor + '.' + minor);
		}
		return String.join(", ", environments); //$NON-NLS-1$
	}


	/**
	 * Bug 470616: [1.9] JavaSE-9 Execution Environment should set proper compiler compliance/source/target levels
	 * <p>
	 * This is a workaround for Bug 495497: [9] JavaSE-9.profile Execution Environment should set compiler levels to 9
	 */
	private void fixJavaSE9ComplianceSourceTargetLevels(Properties profile) {
		if (ExecutionEnvironmentAnalyzer.JavaSE_9.equals(getId())) {
			profile.setProperty(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_9);
			profile.setProperty(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_9);
			profile.setProperty(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_9);
			profile.setProperty(JavaCore.COMPILER_RELEASE, JavaCore.ENABLED);
		}
	}

	@Override
	public IExecutionEnvironment[] getSubEnvironments() {
		Properties properties = getProfileProperties();
		Set<IExecutionEnvironment> subenv = new LinkedHashSet<>();
		if (properties != null) {
			@SuppressWarnings("deprecation")
			String subsets = properties.getProperty(Constants.FRAMEWORK_EXECUTIONENVIRONMENT);
			if (subsets != null) {
				String[] ids = subsets.split(","); //$NON-NLS-1$
				for (String id : ids) {
					IExecutionEnvironment sub = JavaRuntime.getExecutionEnvironmentsManager().getEnvironment(id.trim());
					if (sub != null && !sub.getId().equals(getId())) {
						subenv.add(sub);
					}
				}
			}
		}
		return subenv.toArray(new IExecutionEnvironment[subenv.size()]);
	}

	@Override
	public Map<String, String> getComplianceOptions() {
		Properties properties = getProfileProperties();
		if (properties != null) {
			Map<String, String> map = new HashMap<>();
			properties.forEach((name, value) -> {
				String key = name.toString();
				if (key.startsWith(COMPILER_SETTING_PREFIX)) {
					map.put(key, value.toString());
				}
			});
			if (!map.isEmpty()) {
				return map;
			}
		}
		return null;
	}

	private String getCompliance() {
		return fElement.getAttribute("compliance"); //$NON-NLS-1$
	}

	@Override
	public String toString() {
		return this.fElement.getAttribute("id"); //$NON-NLS-1$
	}
}
