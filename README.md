# Client360

A lightweight banking CRM module for managing customer profiles, interaction history, and audit trails — built as a demonstration of secure, scalable, and auditable backend architecture for financial services.

## Problem

Bank relationship managers often lose 15–20 minutes before every client call or meeting, piecing together information scattered across separate systems: contact and KYC data in one place, product status in another, notes in a spreadsheet. There is no single, auditable view of a customer.

## Solution

Client360 gives managers one place to see a customer's full context and log every interaction, while every change is automatically recorded for compliance and audit purposes.

Core modules:
- **Client Profile** — unified customer card (contact info, KYC status, products)
- **Interaction Log** — timeline of calls, tickets, and notes
- **Task/Reminder** — follow-up tasks with due dates
- **Audit Trail** — immutable log of who changed what and when
- **RBAC** — role-based access (manager / supervisor / admin)

## Architecture

```
REST API (Spring Boot) → PostgreSQL (core data)
        │
        └──> Kafka (change events) → Audit Service (immutable audit log)
```

**Stack**
- Java + Spring Boot — REST API, Spring Security for role-based access
- PostgreSQL — ACID guarantees for financial/personal data
- Kafka — asynchronous audit event pipeline, decoupled from the main API
- Docker Compose — one-command local environment (API + DB + broker)

## Roadmap

- [ ] **MVP** — Client Profile + Interaction Log
- [ ] **v2** — Audit Trail + RBAC
- [ ] **v3** — Task/Reminder + supervisor dashboard with SLA alerts

## Repository structure

```
client360/
├── CLAUDE.md
├── SPEC_TEMPLATE.md
├── .claude/
│   ├── agents/
│   ├── rules/
│   └── skills/
├── src/
│   ├── client-service/
│   ├── interaction-service/
│   └── audit-service/
├── db/
│   └── migrations/
└── docker-compose.yml
```

## Status

Work in progress — built using a spec-first, AI-assisted development workflow with Claude Code.
