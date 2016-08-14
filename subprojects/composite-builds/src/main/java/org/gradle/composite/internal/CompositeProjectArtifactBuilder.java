/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.composite.internal;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import org.gradle.StartParameter;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentSelector;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.ProjectArtifactBuilder;
import org.gradle.api.internal.composite.CompositeBuildContext;
import org.gradle.initialization.GradleLauncher;
import org.gradle.initialization.GradleLauncherFactory;
import org.gradle.internal.component.local.model.DefaultProjectComponentIdentifier;
import org.gradle.internal.component.local.model.DefaultProjectComponentSelector;
import org.gradle.internal.component.model.ComponentArtifactMetadata;
import org.gradle.internal.resolve.ModuleVersionResolveException;
import org.gradle.internal.service.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Set;

public class CompositeProjectArtifactBuilder implements ProjectArtifactBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(CompositeProjectArtifactBuilder.class);

    private final CompositeBuildContext compositeBuildContext;
    private final GradleLauncherFactory gradleLauncherFactory;
    private final StartParameter requestedStartParameter;
    private final ServiceRegistry serviceRegistry;
    private final Multimap<ProjectComponentIdentifier, String> tasksForBuild = LinkedHashMultimap.create();
    private final Set<ProjectComponentIdentifier> executingBuilds = Sets.newHashSet();
    private final Multimap<ProjectComponentIdentifier, String> executedTasks = LinkedHashMultimap.create();

    public CompositeProjectArtifactBuilder(CompositeBuildContext compositeBuildContext,
                                           GradleLauncherFactory gradleLauncherFactory,
                                           StartParameter requestedStartParameter,
                                           ServiceRegistry serviceRegistry) {
        this.compositeBuildContext = compositeBuildContext;
        this.gradleLauncherFactory = gradleLauncherFactory;
        this.requestedStartParameter = requestedStartParameter;
        this.serviceRegistry = serviceRegistry;
    }

    private synchronized void buildStarted(ProjectComponentIdentifier project) {
        if (!executingBuilds.add(project)) {
            ProjectComponentSelector selector = new DefaultProjectComponentSelector(project.getProjectPath());
            throw new ModuleVersionResolveException(selector, "Dependency cycle including " + project);
        }
    }

    private synchronized void buildCompleted(ProjectComponentIdentifier project) {
        executingBuilds.remove(project);
    }

    @Override
    public void willBuild(ComponentArtifactMetadata artifact) {
        if (artifact instanceof CompositeProjectComponentArtifactMetadata) {
            findBuildAndRegisterTasks((CompositeProjectComponentArtifactMetadata) artifact);
        }
    }

    @Override
    public void build(ComponentArtifactMetadata artifact) {
        if (artifact instanceof CompositeProjectComponentArtifactMetadata) {
            ProjectComponentIdentifier buildId = findBuildAndRegisterTasks((CompositeProjectComponentArtifactMetadata) artifact);
            build(buildId, tasksForBuild.get(buildId));
        }
    }

    public ProjectComponentIdentifier findBuildAndRegisterTasks(CompositeProjectComponentArtifactMetadata artifact) {
        ProjectComponentIdentifier buildId = getBuildIdentifier(artifact.getComponentId());
        tasksForBuild.putAll(buildId, artifact.getTasks());
        return buildId;
    }

    private void build(ProjectComponentIdentifier buildId, Iterable<String> taskNames) {
        buildStarted(buildId);
        try {
            doBuild(buildId, taskNames);
        } finally {
            buildCompleted(buildId);
        }
    }

    private void doBuild(ProjectComponentIdentifier buildId, Iterable<String> taskPaths) {
        File buildDirectory = compositeBuildContext.getProjectDirectory(buildId);
        List<String> tasksToExecute = Lists.newArrayList();
        for (String taskPath : taskPaths) {
            if (executedTasks.put(buildId, taskPath)) {
                tasksToExecute.add(taskPath);
            }
        }
        if (tasksToExecute.isEmpty()) {
            return;
        }
        LOGGER.info("Executing " + buildId + " tasks " + taskPaths);

        StartParameter param = requestedStartParameter.newBuild();
        param.setProjectDir(buildDirectory);
        param.setSearchUpwards(false);
        param.setTaskNames(tasksToExecute);

        GradleLauncher launcher = gradleLauncherFactory.nestedInstance(param, serviceRegistry);
        try {
            launcher.run();
        } finally {
            launcher.stop();
        }
    }

    private ProjectComponentIdentifier getBuildIdentifier(ProjectComponentIdentifier project) {
        // TODO:DAZ Introduce a properly typed ComponentIdentifier for project components in a composite
        String buildName = project.getProjectPath().split("::", 2)[0];
        return DefaultProjectComponentIdentifier.newId(buildName + "::");
    }
}
