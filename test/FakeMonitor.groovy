import progressivedelivery.Gateway
import progressivedelivery.Monitor
import progressivedelivery.Pipeline

class FakeMonitor implements Monitor {
    List events
    boolean failDestroy = false

    FakeMonitor(List events) { this.events = events }

    void apply(Map t) { events << "monitor-apply:${t.restApiId}".toString() }
    void destroy(Map t) {
        if (failDestroy) { throw new IllegalStateException('destroy failed') }
        events << "monitor-destroy:${t.restApiId}".toString()
    }
    void silence(Map t) { events << "monitor-silence:${t.restApiId}".toString() }
}
