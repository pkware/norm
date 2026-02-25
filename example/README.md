# Example

A Micronaut application demonstrating how to use Norm in a real project.

`Example.kt` is a playbook showing every Norm API pattern: single results, projections, multi-row
operations, custom mappers, dynamic SQL, DML, batch inserts, and transaction management.

## Prerequisites

- Docker (for the Norm code generation step)
- A running PostgreSQL instance with the schema applied (see `src/main/sql/schema.sql`)
- Connection settings in `src/main/resources/application.properties`

## Developing

To develop Norm, load this project in your IDE directly or use it as your terminal root, rather than going through the
repository root build. This project uses a Gradle Composite Build to include the other Norm projects. That makes it easy
to modify the examples and see your changes in effect while still modifying the runtime/generator/plugin, etc.
