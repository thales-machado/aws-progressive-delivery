package progressivedelivery

class HealthGate {

    /**
     * Judges one canary window. INSUFFICIENT means too little traffic to decide, which is not the
     * same as healthy: the caller extends the bake or fails, depending on requireTraffic.
     */
    static Map evaluate(Map metrics, Map gate) {
        double requests = metrics.requests as double
        double errors = metrics.errors as double
        Double p99 = metrics.p99Ms == null ? null : (metrics.p99Ms as double)
        double rate = requests > 0 ? errors / requests : 0d

        String summary = "requests=${whole(requests)} 5xx=${decimals(rate)}"
        if (p99 != null) {
            summary += " p99=${whole(p99)}ms"
        }

        if (requests < (gate.minRequests as double)) {
            return [
                status : 'INSUFFICIENT',
                summary: summary,
                reasons: ["only ${whole(requests)} requests, minimum is ${gate.minRequests}".toString()],
            ]
        }

        List<String> reasons = []
        if (rate > (gate.max5xxRate as double)) {
            reasons << "5XX rate ${decimals(rate)} above ${gate.max5xxRate}".toString()
        }
        if (gate.maxP99LatencyMs != null && p99 != null && p99 > (gate.maxP99LatencyMs as double)) {
            reasons << "p99 latency ${whole(p99)} ms above ${gate.maxP99LatencyMs} ms".toString()
        }
        return [status: reasons.isEmpty() ? 'PASS' : 'FAIL', summary: summary, reasons: reasons]
    }

    private static String whole(double value) {
        return String.format(Locale.ROOT, '%.0f', value)
    }

    private static String decimals(double value) {
        return String.format(Locale.ROOT, '%.4f', value)
    }
}
