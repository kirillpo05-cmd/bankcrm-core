# Client360 — Project Description

## Overview

Client360 is a lightweight, banking-grade CRM system designed to give relationship managers a single, unified view of every customer they work with — their contact details, product portfolio, KYC status, and the complete history of every interaction, all in one place, with a full audit trail behind every change.

The project is inspired by a real pain point in financial services: customer data is often scattered across multiple systems — one for contact records, another for support tickets, a spreadsheet for personal notes — forcing managers to spend up to 20 minutes reconstructing context before every call. Client360 solves this by consolidating that context into one auditable customer card, built with the same architectural principles used in production banking software: strict role-based access, data integrity, and traceability of every change.

## Why this project exists

This project was built as a hands-on exploration of how modern financial institutions structure their CRM and customer-data systems — the kind of software that underpins day-to-day banking operations. Rather than building "yet another CRUD app," the goal was to replicate the constraints that make banking software genuinely hard to get right:

- **Security first** — every piece of personal data is a liability if mishandled. Sensitive fields are encrypted at rest, and access is strictly role-scoped.
- **Auditability by design** — in regulated industries, "who changed what and when" isn't an afterthought; it's a first-class requirement. Every mutation to customer data produces an immutable audit event.
- **Scalability without over-engineering** — the system is split into independent services (client, interaction, audit) connected through an event stream, so it can grow without becoming a tangled monolith, and without introducing unnecessary complexity for a project of this scope.
- **Reliability** — core customer data lives in a strongly consistent relational store, while non-blocking, asynchronous processing handles auditing and notifications so the main workflow never gets slowed down.

## What it does

At its core, Client360 answers one question for a bank employee: *"Who is this customer, and what has happened with them?"* — instantly.

- **Client Profile** — a unified customer card with contact info, KYC status, and linked products.
- **Interaction Log** — a chronological feed of every call, support ticket, and internal note tied to a customer.
- **Task & Reminders** — follow-up actions with due dates, so nothing falls through the cracks.
- **Audit Trail** — a tamper-evident log of every change made to a customer record, including who made it and when.
- **Role-Based Access Control** — managers, supervisors, and admins each see and do only what their role permits.

A supervisor, meanwhile, gets a bird's-eye view: overdue tasks across their whole team, surfaced automatically instead of chased down manually.

## How it's built

Client360 follows a **spec-first development methodology**: before a single line of code was written, the project went through a structured pipeline — problem definition → technical specification (data models, API contracts, edge cases) → configuration for AI-assisted development — ensuring the architecture was sound before implementation began. This mirrors how serious engineering teams work: design decisions are made deliberately, not discovered by accident three days into coding.

The technical foundation:

- **Java + Spring Boot** for the REST API layer, with Spring Security enforcing role-based permissions on every endpoint.
- **PostgreSQL** as the system of record — chosen for its ACID guarantees, which matter when the data in question is financial and personal.
- **Apache Kafka** as an event backbone, decoupling the audit-logging pipeline from the main request path so writes stay fast and auditing stays reliable.
- **Docker Compose** for a fully reproducible local environment — API, database, and message broker, up with a single command.

The system is deliberately split into independently deployable services — `client-service`, `interaction-service`, and `audit-service` — rather than one monolithic application, so each concern (customer data, interaction history, compliance logging) can evolve on its own without destabilizing the others.

## What this project demonstrates

- Designing a data model and API around real compliance and security constraints, not just feature completeness.
- Structuring a backend system into services with clear boundaries and event-driven communication.
- Thinking through edge cases that matter in production: duplicate records, concurrent edits, unauthorized access attempts, and asynchronous failure modes.
- Applying a disciplined, specification-driven engineering process rather than ad-hoc, code-first development.

## Roadmap

| Phase | Scope |
|---|---|
| **MVP** | Client Profile + Interaction Log — the core "single view of the customer" |
| **v2** | Audit Trail + Role-Based Access Control |
| **v3** | Tasks/Reminders + supervisor dashboard with SLA alerting |

## Status

Actively in development. Built and documented following a spec-first, AI-assisted engineering workflow.
