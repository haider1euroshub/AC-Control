# Decision Log

This log tracks architectural, protocol, design, and scope decisions made during the project. Every decision records the context, the decision made, consequences, and owner approval where applicable.

---

## [DEC-001] Project Inception, Scope Boundaries & Phase Gates
- **Date:** 2026-11-07
- **Status:** Approved (Master Prompt baseline)
- **Context:** Project initiation for custom Android "Room AC Controller" application in Pakistan.
- **Decision:**
  1. The app shall strictly implement exactly four core capabilities:
     - Power ON / OFF
     - Mode: Heating / Cooling
     - Temperature: Increase / Decrease / Display target
     - Fan Movement (Swing): Only what the physical AC supports
  2. All out-of-scope features (iOS, multiple ACs, user accounts, usage analytics, scheduling, sleep/eco/turbo modes, fan speed, home screen widgets, notifications, automation) are strictly barred unless approved in writing by the owner.
  3. Work must proceed strictly in gates:
     - Phase 0: Owner Information Checklist
     - Phase 1: Connectivity Investigation & Feasibility Report
     - GATE 1: Owner Decision
     - Phase 2: Planning & Design Proposal
     - GATE 2: Owner Approval
     - Phase 3: Incremental Implementation against Real AC
     - Phase 4: Verification Matrix & Acceptance
  4. Absolutely no app code is to be written until Gate 2 approval. No protocol or API assumptions allowed.
- **Consequences:** Implementation begins only after feasibility investigation and owner approval. Focus remains exclusively on verified physical control.

