package progressivedelivery

/**
 * Everything the orchestration needs from the CI system. Jenkins implements it with pipeline
 * steps; tests implement it with an in-memory fake, so the release logic runs without Jenkins.
 */
interface Pipeline {

    void log(String message)

    void unstable(String message)

    /** Runs a shell command and returns its trimmed stdout. Fails when the exit code is not zero. */
    String sh(String script)

    void sleepSeconds(int seconds)

    long nowEpochSeconds()

    /** Asks for a decision. The first choice is the safe default and is also returned when the prompt times out. */
    String choose(String message, List choices, int timeoutMinutes)

    void runStage(String name, Closure body)

    /** Runs the branches at the same time and stops the others when one fails. */
    void runParallel(Map branches)

    void withEnvVars(List vars, Closure body)

    void writeText(String path, String text)

    /** Reads a file shipped in the library's resources directory. */
    String resource(String path)

    void archive(String path)

    String buildTag()
}
