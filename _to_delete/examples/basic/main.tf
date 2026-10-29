resource "aws_api_gateway_rest_api" "this" {
  name = "apigw-canary-example"

  body = jsonencode({
    openapi = "3.0.1"
    info    = {
      title   = "apigw-canary-example"
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

resource "aws_api_gateway_deployment" "initial" {
  rest_api_id = aws_api_gateway_rest_api.this.id
  description = "initial"

  lifecycle {
    create_before_destroy = true
  }
}

module "stage" {
  source = "../.."

  rest_api_id          = aws_api_gateway_rest_api.this.id
  stage_name           = var.stage_name
  stable_deployment_id = coalesce(var.stable_deployment_id, aws_api_gateway_deployment.initial.id)
  canary               = var.canary

  stage_variables = {
    version = "v1"
  }
}
