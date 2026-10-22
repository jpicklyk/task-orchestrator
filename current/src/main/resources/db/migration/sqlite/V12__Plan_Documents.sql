-- V12: Plan documents store — agent-authored planning docs stashed per project root
--
-- Adds `plan_documents`, a per-root table holding markdown/text planning documents an agent
-- stashes ahead of adoption into a real work item (see PlanDocumentService / the
-- `manage_plan_documents` MCP tool and the REST `PUT/GET /api/v1/roots/{rootId}/plans/{slug}`
-- routes — neither is touched by this migration). Slugs are caller-chosen identifiers, unique per
-- root; `status` tracks whether the document is still a free-floating stash (`pending`) or has been
-- adopted into a work item (`adopted`, permanently — the service layer enforces the one-way
-- transition, not a DB trigger or CHECK constraint).
--
-- Pure CREATE TABLE — no ALTER COLUMN, no table recreation. Follows the exact conventions of
-- V10__Project_Config.sql: BLOB id with a randomblob(16) default, a plain (non-inline-UNIQUE)
-- pair of columns plus a separate CREATE UNIQUE INDEX statement for the uniqueness constraint.
--
-- Two FKs to work_items, with DELIBERATELY different delete actions:
--   - root_item_id       ON DELETE CASCADE   — the document belongs to its root; deleting the root
--                                              (and therefore its whole tree) removes the document.
--   - adopted_by_item_id ON DELETE SET NULL  — the adopting work item is a separate lifecycle from
--                                              the document itself. Deleting the adopting item must
--                                              not destroy the archived document, only unlink the
--                                              adoption (the row still cascades away with its ROOT
--                                              via root_item_id, exactly like any other document).
--
-- The 64KB body size cap is enforced at the service layer (PlanDocumentService), mirroring
-- ManageNotesTool's bodyFromFile cap — kept code-side rather than a SQL CHECK for consistency with
-- how note bodies are capped elsewhere in this codebase.

CREATE TABLE plan_documents (
    id                  BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    root_item_id        BLOB NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    slug                TEXT NOT NULL,
    body                TEXT NOT NULL,
    content_hash        TEXT NOT NULL,
    status              TEXT NOT NULL CHECK (status IN ('pending', 'adopted')),
    adopted_by_item_id  BLOB REFERENCES work_items(id) ON DELETE SET NULL,
    created_at          TIMESTAMP NOT NULL,
    modified_at         TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX idx_plan_documents_root_item_id_slug ON plan_documents(root_item_id, slug);
