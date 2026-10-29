output "alarm_names" {
  description = "Names of the alarms created for the release."
  value       = concat(
    [aws_cloudwatch_metric_alarm.error_rate.alarm_name],
    aws_cloudwatch_metric_alarm.p99_latency[*].alarm_name,
  )
}
