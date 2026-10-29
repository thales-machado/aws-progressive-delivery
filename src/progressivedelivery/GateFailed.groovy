package progressivedelivery

class GateFailed extends IllegalStateException {

    GateFailed(String message) {
        super(message)
    }
}
