-- Hard-delete must always drop the denormalized search row with the skill.
-- The previous NO ACTION FK required an in-transaction index remove first; a
-- concurrent rebuild could re-insert the document and leave the namespace
-- list pointing at a skill the detail page can no longer resolve.
ALTER TABLE skill_search_document
    DROP CONSTRAINT IF EXISTS skill_search_document_skill_id_fkey;

ALTER TABLE skill_search_document
    ADD CONSTRAINT skill_search_document_skill_id_fkey
        FOREIGN KEY (skill_id) REFERENCES skill(id) ON DELETE CASCADE;
