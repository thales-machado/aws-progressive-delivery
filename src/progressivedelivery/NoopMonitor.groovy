package progressivedelivery

class NoopMonitor implements Monitor, Serializable {

    void apply(Map target) {
    }

    void destroy(Map target) {
    }

    void silence(Map target) {
    }
}
