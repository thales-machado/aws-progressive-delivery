package progressivedelivery

/** A person (or an approval timeout) stopped the release. Unlike a gate failure, nothing is urgent. */
class ReleaseAborted extends Exception {

    ReleaseAborted(String message) {
        super(message)
    }
}
