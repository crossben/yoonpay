# Changelog

## 0.1.0 (unreleased)

- First release of the Spring Boot starter: an auto-configured `Yoon` bean (from `yoon.url`,
  `yoon.api-key`, `yoon.timeout`) and a webhook filter for the paths in `yoon.webhook.paths`
  (signature check, duplicate drop, id remembered only after a 2xx answer), with `YoonEvent`
  injectable into controllers. Tested with Spring Boot 3.5 and 4.1.
