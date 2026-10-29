package progressivedelivery

/** The CloudWatch alarms that watch a canary between and during gate evaluations. */
interface Monitor {

    void apply(Map target)

    void destroy(Map target)

    /** Stops the alarm notifications without touching Terraform. */
    void silence(Map target)
}
