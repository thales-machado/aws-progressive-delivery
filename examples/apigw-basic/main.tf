resource "aws_api_gateway_rest_api" "this" {
  name = var.api_name

  body = jsonencode({
    openapi = "3.0.1"
    info    = {
      title   = var.api_name
      version = "1.0"
    }
    paths = {
      "/health" = {
        get = {
          responses = {
            "200" = { description = "OK" }
          }
          x-amazon-apigateway-integration = {
            type             = "mock"
            requestTemplates = {
              "application/json" = "{\"statusCode\": 200}"
            }
            responses = {
              default = {
                statusCode        = "200"
                responseTemplates = {
                  "application/json" = "{\"version\": \"$stageVariables.version\", \"canary\": $context.isCanaryRequest}"
                }
              }
            }
          }
        }
      }
    }
  })

  endpoint_configuration {
    types = ["REGIONAL"]
  }
}

# A stage needs a deployment to exist, so Terraform creates the first one.
# It has no triggers on purpose: later definition changes are deployed by the release pipeline.
resource "aws_api_gateway_deployment" "initial" {
  rest_api_id = aws_api_gateway_rest_api.this.id
  description = "initial"

  lifecycle {
    create_before_destroy = true
  }
}

module "stage" {
  source = "../../modules/apigw-canary"

  rest_api_id           = aws_api_gateway_rest_api.this.id
  stage_name            = var.stage_name
  initial_deployment_id = aws_api_gateway_deployment.initial.id

  stage_variables = {
    version = var.version_label
  }
}
