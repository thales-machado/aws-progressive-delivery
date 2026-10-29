import progressivedelivery.Gateway
import progressivedelivery.Monitor
import progressivedelivery.Pipeline

class FakePipeline implements Pipeline {
    List events
    List logs = []
    List unstableMessages = []
    List scripts = []
    Map files = [:]
    long clock = 1_000_000L
    Closure decider = { String message, List choices -> choices[1] }
    List prompts = []
    String shResult = ''
    File resourceRoot

    FakePipeline(List events, File resourceRoot) {
        this.events = events
        this.resourceRoot = resourceRoot
    }

    void log(String message) { logs << message }
    void unstable(String message) { unstableMessages << message; events << "unstable" }
    String sh(String script) { scripts << script; return shResult }
    void sleepSeconds(int seconds) { clock += seconds }
    long nowEpochSeconds() { return clock }

    String choose(String message, List choices, int timeoutMinutes) {
        prompts << [message: message, choices: choices]
        String answer = decider(message, choices)
        events << "choose:${answer}".toString()
        return answer
    }

    void runStage(String name, Closure body) { body() }
    void runParallel(Map branches) { events << "parallel:${branches.keySet().join('+')}".toString(); branches.values().each { it() } }
    void withEnvVars(List vars, Closure body) { body() }
    void writeText(String path, String text) { files[path] = text }
    String resource(String path) { return new File(resourceRoot, path).text }
    void archive(String path) { }
    String buildTag() { return 'jenkins-job-42' }
}
