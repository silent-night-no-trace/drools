/*
* This file is describing all the Jenkins jobs in the DSL format (see https://plugins.jenkins.io/job-dsl/)
* needed by the Kogito pipelines.
*
* The main part of Jenkins job generation is defined into the https://github.com/apache/incubator-kie-kogito-pipelines repository.
*
* This file is making use of shared libraries defined in
* https://github.com/apache/incubator-kie-kogito-pipelines/tree/main/dsl/seed/src/main/groovy/org/kie/jenkins/jobdsl.
*/

import org.kie.jenkins.jobdsl.model.JenkinsFolder
import org.kie.jenkins.jobdsl.model.JobType
import org.kie.jenkins.jobdsl.utils.EnvUtils
import org.kie.jenkins.jobdsl.utils.JobParamsUtils
import org.kie.jenkins.jobdsl.KogitoJobTemplate
import org.kie.jenkins.jobdsl.KogitoJobUtils
import org.kie.jenkins.jobdsl.Utils

jenkins_path = '.ci/jenkins'

boolean isMainStream() {
    return Utils.getStream(this) == 'main'
}

///////////////////////////////////////////////////////////////////////////////////////////
// Whole Drools project jobs
///////////////////////////////////////////////////////////////////////////////////////////

jenkins_path_project = "${jenkins_path}/project"

// Init branch
createProjectSetupBranchJob()

// Nightly jobs
setupProjectNightlyJob()

// Release jobs
setupProjectReleaseJob()
setupProjectPostReleaseJob()

// Tools
KogitoJobUtils.createQuarkusPlatformUpdateToolsJob(this, 'drools')
KogitoJobUtils.createMainQuarkusUpdateToolsJob(this,
        [ 'drools' ],
        [ 'mariofusco', 'danielezonca']
)

void createProjectSetupBranchJob() {
    def jobParams = JobParamsUtils.getBasicJobParams(this, '0-setup-branch', JobType.SETUP_BRANCH, "${jenkins_path_project}/Jenkinsfile.setup-branch", 'Drools Setup Branch')
    jobParams.env.putAll([
        JENKINS_EMAIL_CREDS_ID: "${JENKINS_EMAIL_CREDS_ID}",

        GIT_BRANCH_NAME: "${GIT_BRANCH}",

        IS_MAIN_BRANCH: "${Utils.isMainBranch(this)}",
        DROOLS_STREAM: Utils.getStream(this),
    ])
    KogitoJobTemplate.createPipelineJob(this, jobParams)?.with {
        parameters {
            stringParam('DROOLS_VERSION', '', 'Drools version')
            booleanParam('DEPLOY', true, 'Deploy artifacts')
        }
    }
}

void setupProjectNightlyJob() {
    def jobParams = JobParamsUtils.getBasicJobParams(this, '0-nightly', JobType.NIGHTLY, "${jenkins_path_project}/Jenkinsfile.nightly", 'Drools Nightly')
    jobParams.triggers = [cron : isMainStream() ? '@midnight' : 'H 3 * * *']
    jobParams.env.putAll([
        JENKINS_EMAIL_CREDS_ID: "${JENKINS_EMAIL_CREDS_ID}",

        GIT_BRANCH_NAME: "${GIT_BRANCH}",

        DROOLS_STREAM: Utils.getStream(this),
    ])
    KogitoJobTemplate.createPipelineJob(this, jobParams)?.with {
        parameters {
            booleanParam('SKIP_TESTS', false, 'Skip all tests')
        }
    }
}

void setupProjectReleaseJob() {
    def jobParams = JobParamsUtils.getBasicJobParams(this, '0-drools-release', JobType.RELEASE, "${jenkins_path_project}/Jenkinsfile.release", 'Drools/Kogito Artifacts Release')
    jobParams.env.putAll([
        JENKINS_EMAIL_CREDS_ID: "${JENKINS_EMAIL_CREDS_ID}",

        GIT_BRANCH_NAME: "${GIT_BRANCH}",
        GIT_AUTHOR: "${GIT_AUTHOR_NAME}",

        DEFAULT_STAGING_REPOSITORY: "${MAVEN_NEXUS_STAGING_PROFILE_URL}",
        ARTIFACTS_REPOSITORY: "${MAVEN_ARTIFACTS_REPOSITORY}",

        DROOLS_STREAM: Utils.getStream(this),
    ])
    KogitoJobTemplate.createPipelineJob(this, jobParams)?.with {
        parameters {
            stringParam('RESTORE_FROM_PREVIOUS_JOB', '', 'URL to a previous stopped release job which needs to be continued')

            stringParam('DROOLS_VERSION', '', 'Drools version to release as Major.minor.micro')

            booleanParam('SKIP_TESTS', false, 'Skip all tests')
        }
    }
}

void setupProjectPostReleaseJob() {
    def jobParams = JobParamsUtils.getBasicJobParams(this, 'drools-post-release', JobType.RELEASE, "${jenkins_path_project}/Jenkinsfile.post-release", 'Drools Post Release')
    JobParamsUtils.setupJobParamsAgentDockerBuilderImageConfiguration(this, jobParams)
    jobParams.env.putAll([
        JENKINS_EMAIL_CREDS_ID: "${JENKINS_EMAIL_CREDS_ID}",

        GIT_BRANCH_NAME: "${GIT_BRANCH}",
        GIT_AUTHOR: "${GIT_AUTHOR_NAME}",
        AUTHOR_CREDS_ID: "${GIT_AUTHOR_CREDENTIALS_ID}",

        DROOLS_STREAM: Utils.getStream(this),
    ])
    KogitoJobTemplate.createPipelineJob(this, jobParams)?.with {
        parameters {
            stringParam('DROOLS_VERSION', '', 'Drools version to release as Major.minor.micro')

            stringParam('RELEASE_NOTES_NUMBER', '', 'number of JIRA release notes')
        }
    }
}

///////////////////////////////////////////////////////////////////////////////////////////
// Drools repository only project jobs
///////////////////////////////////////////////////////////////////////////////////////////

Map getMultijobPRConfig(JenkinsFolder jobFolder) {
    def jobConfig = [
        parallel: true,
        buildchain: true,
        jobs : [
            [
                id: 'drools',
                primary: true,
                env : [
                    // Sonarcloud analysis only on main branch
                    // As we have only Community edition
                    ENABLE_SONARCLOUD: EnvUtils.isDefaultEnvironment(this, jobFolder.getEnvironmentName()) && Utils.isMainBranch(this),
                ]
            ], [
                id: 'kogito-runtimes',
                repository: 'incubator-kie-kogito-runtimes'
            ], [
                id: 'kogito-apps',
                repository: 'incubator-kie-kogito-apps',
            ], [
                id: 'kogito-quarkus-examples',
                repository: 'incubator-kie-kogito-examples',
                env : [
                    KOGITO_EXAMPLES_SUBFOLDER_POM: 'kogito-quarkus-examples/',
                ],
            ], [
                id: 'kogito-springboot-examples',
                repository: 'incubator-kie-kogito-examples',
                env : [
                    KOGITO_EXAMPLES_SUBFOLDER_POM: 'kogito-springboot-examples/',
                ],
            ], [
                id: 'serverless-workflow-examples',
                repository: 'incubator-kie-kogito-examples',
                env : [
                    KOGITO_EXAMPLES_SUBFOLDER_POM: 'serverless-workflow-examples/',
                ],
            // Commented as not migrated
            // ], [
            //     id: 'kie-jpmml-integration',
            //     repository: 'incubator-kie-jpmml-integration'
            ]
        ]
    ]

    // For Quarkus 3, run only drools PR check... for now
    if (EnvUtils.hasEnvironmentId(this, jobFolder.getEnvironmentName(), 'quarkus3')) {
        jobConfig.jobs.retainAll { it.id == 'drools' }
    }

    return jobConfig
}

// PR checks
Utils.isMainBranch(this) && KogitoJobTemplate.createPullRequestMultibranchPipelineJob(this, "${jenkins_path}/Jenkinsfile")

// Init branch
createSetupBranchJob()

// Nightly jobs
Closure setup3AMCronTriggerJobParamsGetter = { script ->
    def jobParams = JobParamsUtils.DEFAULT_PARAMS_GETTER(script)
    jobParams.triggers = [ cron: 'H 3 * * *' ]
    return jobParams
}

Closure nightlyJobParamsGetter = isMainStream() ? JobParamsUtils.DEFAULT_PARAMS_GETTER : setup3AMCronTriggerJobParamsGetter
KogitoJobUtils.createNightlyBuildChainBuildAndDeployJobForCurrentRepo(this, '', true)
setupSpecificBuildChainNightlyJob('native', nightlyJobParamsGetter)
setupSpecificBuildChainNightlyJob('sonarcloud', nightlyJobParamsGetter)
setupQuarkusIntegrationJob('quarkus-main', nightlyJobParamsGetter)
setupQuarkusIntegrationJob('quarkus-branch', nightlyJobParamsGetter)
setupQuarkusIntegrationJob('quarkus-lts', nightlyJobParamsGetter)
setupQuarkusIntegrationJob('native-lts', nightlyJobParamsGetter)
// Quarkus 3 nightly is exported to Kogito pipelines for easier integration

// Release jobs
setupDeployJob(JobType.RELEASE)
setupPromoteJob(JobType.RELEASE)

// Tools job
if (isMainStream()) {
    KogitoJobUtils.createQuarkusUpdateToolsJob(this, 'drools', [
        modules: [ 'drools-build-parent' ],
        compare_deps_remote_poms: [ 'io.quarkus:quarkus-bom' ],
        properties: [ 'version.io.quarkus' ],
    ])

    // Quarkus 3
    if (EnvUtils.isEnvironmentEnabled(this, 'quarkus-3')) {
        // TODO create PR job with branch source plugin. How to ?
        // setupPrQuarkus3RewriteJob()
        setupStandaloneQuarkus3RewriteJob()
    }
}

/////////////////////////////////////////////////////////////////
// Methods
/////////////////////////////////////////////////////////////////

void setupQuarkusIntegrationJob(String envName, Closure defaultJobParamsGetter = JobParamsUtils.DEFAULT_PARAMS_GETTER) {
    KogitoJobUtils.createNightlyBuildChainIntegrationJob(this, envName, Utils.getRepoName(this), true, defaultJobParamsGetter)
}

void setupSpecificBuildChainNightlyJob(String envName, Closure defaultJobParamsGetter = JobParamsUtils.DEFAULT_PARAMS_GETTER) {
    KogitoJobUtils.createNightlyBuildChainBuildAndTestJobForCurrentRepo(this, envName, true, defaultJobParamsGetter)
}

void createSetupBranchJob() {
    def jobParams = JobParamsUtils.getBasicJobParams(this, 'drools', JobType.SETUP_BRANCH, "${jenkins_path}/Jenkinsfile.setup-branch", 'Drools Setup branch')
    JobParamsUtils.setupJobParamsAgentDockerBuilderImageConfiguration(this, jobParams)
    jobParams.env.putAll([
        JENKINS_EMAIL_CREDS_ID: "${JENKINS_EMAIL_CREDS_ID}",

        GIT_AUTHOR: "${GIT_AUTHOR_NAME}",
        AUTHOR_CREDS_ID: "${GIT_AUTHOR_CREDENTIALS_ID}",

        MAVEN_SETTINGS_CONFIG_FILE_ID: "${MAVEN_SETTINGS_FILE_ID}",

        IS_MAIN_BRANCH: "${Utils.isMainBranch(this)}",
        DROOLS_STREAM: Utils.getStream(this),
    ])
    KogitoJobTemplate.createPipelineJob(this, jobParams)?.with {
        parameters {
            stringParam('DISPLAY_NAME', '', 'Setup a specific build display name')

            stringParam('BUILD_BRANCH_NAME', "${GIT_BRANCH}", 'Set the Git branch to checkout')

            stringParam('DROOLS_VERSION', '', 'Drools version to set.')

            booleanParam('SEND_NOTIFICATION', false, 'In case you want the pipeline to send a notification on CI channel for this run.')
        }
    }
}

void setupDeployJob(JobType jobType) {
    def jobParams = JobParamsUtils.getBasicJobParams(this, 'drools-deploy', jobType, "${jenkins_path}/Jenkinsfile.deploy", 'Drools Deploy')
    JobParamsUtils.setupJobParamsAgentDockerBuilderImageConfiguration(this, jobParams)
    jobParams.env.putAll([
        PROPERTIES_FILE_NAME: 'deployment.properties',
        JENKINS_EMAIL_CREDS_ID: "${JENKINS_EMAIL_CREDS_ID}",

        GIT_AUTHOR: "${GIT_AUTHOR_NAME}",
        AUTHOR_CREDS_ID: "${GIT_AUTHOR_CREDENTIALS_ID}",
        GITHUB_TOKEN_CREDS_ID: "${GIT_AUTHOR_TOKEN_CREDENTIALS_ID}",

        MAVEN_SETTINGS_CONFIG_FILE_ID: "${MAVEN_SETTINGS_FILE_ID}",
        MAVEN_DEPENDENCIES_REPOSITORY: "${MAVEN_ARTIFACTS_REPOSITORY}",
        MAVEN_DEPLOY_REPOSITORY: "${MAVEN_ARTIFACTS_UPLOAD_REPOSITORY_URL}",
        MAVEN_REPO_CREDS_ID: "${MAVEN_ARTIFACTS_UPLOAD_REPOSITORY_CREDS_ID}",

        DROOLS_STREAM: Utils.getStream(this),
    ])
    if (jobType == JobType.RELEASE) {
        jobParams.env.putAll([
            NEXUS_RELEASE_URL: "${MAVEN_NEXUS_RELEASE_URL}",
            NEXUS_RELEASE_REPOSITORY_ID: "${MAVEN_NEXUS_RELEASE_REPOSITORY}",
            NEXUS_STAGING_PROFILE_ID: "${MAVEN_NEXUS_STAGING_PROFILE_ID}",
            NEXUS_BUILD_PROMOTION_PROFILE_ID: "${MAVEN_NEXUS_BUILD_PROMOTION_PROFILE_ID}",
        ])
    }
    KogitoJobTemplate.createPipelineJob(this, jobParams)?.with {
        parameters {
            stringParam('DISPLAY_NAME', '', 'Setup a specific build display name')

            stringParam('BUILD_BRANCH_NAME', "${GIT_BRANCH}", 'Set the Git branch to checkout')

            booleanParam('SKIP_TESTS', false, 'Skip tests')

            booleanParam('CREATE_PR', false, 'Should we create a PR with the changes ?')
            stringParam('PROJECT_VERSION', '', 'Optional if not RELEASE. If RELEASE, cannot be empty.')
            stringParam('DROOLS_PR_BRANCH', '', 'PR branch name')

            booleanParam('SEND_NOTIFICATION', false, 'In case you want the pipeline to send a notification on CI channel for this run.')
        }
    }
}

void setupPromoteJob(JobType jobType) {
    def jobParams = JobParamsUtils.getBasicJobParams(this, 'drools-promote', jobType, "${jenkins_path}/Jenkinsfile.promote", 'Drools Promote')
    JobParamsUtils.setupJobParamsAgentDockerBuilderImageConfiguration(this, jobParams)
    jobParams.env.putAll([
        PROPERTIES_FILE_NAME: 'deployment.properties',
        JENKINS_EMAIL_CREDS_ID: "${JENKINS_EMAIL_CREDS_ID}",

        GIT_AUTHOR: "${GIT_AUTHOR_NAME}",
        AUTHOR_CREDS_ID: "${GIT_AUTHOR_CREDENTIALS_ID}",
        GITHUB_TOKEN_CREDS_ID: "${GIT_AUTHOR_TOKEN_CREDENTIALS_ID}",

        MAVEN_SETTINGS_CONFIG_FILE_ID: "${MAVEN_SETTINGS_FILE_ID}",
        MAVEN_DEPENDENCIES_REPOSITORY: "${MAVEN_ARTIFACTS_REPOSITORY}",
        MAVEN_DEPLOY_REPOSITORY: "${MAVEN_ARTIFACTS_REPOSITORY}",

        DROOLS_STREAM: Utils.getStream(this),
    ])
    KogitoJobTemplate.createPipelineJob(this, jobParams)?.with {
        parameters {
            stringParam('DISPLAY_NAME', '', 'Setup a specific build display name')
            stringParam('BUILD_BRANCH_NAME', "${GIT_BRANCH}", 'Set the Git branch to checkout')
            // Deploy job url to retrieve deployment.properties
            stringParam('DEPLOY_BUILD_URL', '', 'URL to jenkins deploy build to retrieve the `deployment.properties` file. If base parameters are defined, they will override the `deployment.properties` information')
            // Release information which can override `deployment.properties`
            stringParam('PROJECT_VERSION', '', 'Override `deployment.properties`. Optional if not RELEASE. If RELEASE, cannot be empty.')
            stringParam('GIT_TAG', '', 'Git tag to set, if different from PROJECT_VERSION')
            booleanParam('SEND_NOTIFICATION', false, 'In case you want the pipeline to send a notification on CI channel for this run.')
        }
    }
}

void setupPrQuarkus3RewriteJob() {
    def jobParams = JobParamsUtils.getBasicJobParamsWithEnv(this, 'drools.rewrite', JobType.PULL_REQUEST, 'quarkus-3', "${jenkins_path}/Jenkinsfile.quarkus-3.rewrite.pr", 'Drools Quarkus 3 rewrite patch regeneration')
    JobParamsUtils.setupJobParamsAgentDockerBuilderImageConfiguration(this, jobParams)
    jobParams.jenkinsfile = "${jenkins_path}/Jenkinsfile.quarkus-3.rewrite.pr"
    jobParams.pr.putAll([
        run_only_for_branches: [ "${GIT_BRANCH}" ],
        disable_status_message_error: true,
        disable_status_message_failure: true,
        trigger_phrase: '.*[j|J]enkins,?.*(rewrite|write) [Q|q]uarkus-3.*',
        trigger_phrase_only: true,
        commitContext: 'Quarkus 3 rewrite',
    ])
    jobParams.env.putAll([
        AUTHOR_CREDS_ID: "${GIT_AUTHOR_CREDENTIALS_ID}",
        MAVEN_SETTINGS_CONFIG_FILE_ID: "${MAVEN_SETTINGS_FILE_ID}",
    ])
    KogitoJobTemplate.createPRJob(this, jobParams)
}

void setupStandaloneQuarkus3RewriteJob() {
    def jobParams = JobParamsUtils.getBasicJobParams(this, 'drools.quarkus-3.rewrite', JobType.TOOLS, "${jenkins_path}/Jenkinsfile.quarkus-3.rewrite.standalone", 'Drools Quarkus 3 rewrite patch regeneration')
    JobParamsUtils.setupJobParamsAgentDockerBuilderImageConfiguration(this, jobParams)
    jobParams.env.putAll(EnvUtils.getEnvironmentEnvVars(this, 'quarkus-3'))
    jobParams.env.putAll([
        AUTHOR_CREDS_ID: "${GIT_AUTHOR_CREDENTIALS_ID}",
        JENKINS_EMAIL_CREDS_ID: "${JENKINS_EMAIL_CREDS_ID}",
        MAVEN_SETTINGS_CONFIG_FILE_ID: "${MAVEN_SETTINGS_FILE_ID}",
    ])
    KogitoJobTemplate.createPipelineJob(this, jobParams)?.with {
        parameters {
            stringParam('DISPLAY_NAME', '', 'Setup a specific build display name')
            stringParam('GIT_AUTHOR', "${GIT_AUTHOR_NAME}", 'Set the Git author to checkout')
            stringParam('BUILD_BRANCH_NAME', "${GIT_BRANCH}", 'Set the Git branch to checkout')
            booleanParam('IS_PR_SOURCE_BRANCH', false, 'Set to true if you are launching the job for a PR source branch')
            booleanParam('SEND_NOTIFICATION', false, 'In case you want the pipeline to send a notification on CI channel for this run.')
        }
    }
}
