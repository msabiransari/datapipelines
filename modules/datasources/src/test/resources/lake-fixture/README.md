# lake-fixture — a COPY of tests/integration-tests' 089 §F Iceberg fixture

Test resources do not cross module bounds (the same reason this module has its
own OidcDiscoveryStub), so the lake connectivity probe keeps its own copy of
`iceberg/trips_iceberg`. The ORIGINAL — with the regeneration recipe — lives at
`tests/integration-tests/src/test/resources/lake-fixture/`; the bucket name
`dp-lake-it` is baked into the table's metadata URIs and is load-bearing.
