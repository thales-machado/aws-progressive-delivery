package progressivedelivery

import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

class JenkinsPipeline implements Pipeline, Serializable {

    private def steps

    JenkinsPipeline(def steps) {
        this.steps = steps
    }

    void log(String message) {
        steps.echo(message)
    }

    void unstable(String message) {
        steps.unstable(message)
    }

    String sh(String script) {
        String out = steps.sh(script: script, returnStdout: true)
        return out.trim()
    }

    void sleepSeconds(int seconds) {
        steps.sleep(time: seconds, unit: 'SECONDS')
    }

    long nowEpochSeconds() {
        return System.currentTimeMillis().intdiv(1000L)
    }

    String choose(String message, List choices, int timeoutMinutes) {
        String answer = choices[0]
        try {
            steps.timeout(time: timeoutMinutes, unit: 'MINUTES') {
                answer = steps.input(
                    message: message,
                    parameters: [steps.choice(name: 'DECISION', choices: choices.join('\n'), description: message)]
                )
            }
        } catch (FlowInterruptedException ignored) {
            steps.echo("No decision received, falling back to ${choices[0]}")
            answer = choices[0]
        }
        return answer
    }

    void runStage(String name, Closure body) {
        steps.stage(name) {
            body()
        }
    }

    void runParallel(Map branches) {
        Map all = new LinkedHashMap(branches)
        all.failFast = true
        steps.parallel(all)
    }

    void withEnvVars(List vars, Closure body) {
        steps.withEnv(vars) {
            body()
        }
    }

    void writeText(String path, String text) {
        steps.writeFile(file: path, text: text)
    }

    String resource(String path) {
        return steps.libraryResource(path)
    }

    void archive(String path) {
        steps.archiveArtifacts(artifacts: path, allowEmptyArchive: true)
    }

    String buildTag() {
        return steps.env.BUILD_TAG
    }
}
