-- Registry association now uses only the skill name (slug), across all namespaces.
-- Prefixes are metadata: plugin:skill and @namespace/skill both resolve to skill.
-- Backfill existing events without changing invocation counts, evidence or timestamps.
CREATE INDEX IF NOT EXISTS idx_skill_slug ON skill(slug);

WITH unique_skills AS (
    SELECT slug, min(id) AS id FROM skill GROUP BY slug HAVING count(*) = 1
), resolved AS (
    SELECT e.id, s.id AS skill_id
    FROM skill_invocation_event e
    LEFT JOIN unique_skills s
      ON s.slug = regexp_replace(e.skill_name, '^(@[^/]+/|[^:]+:)', '')
)
UPDATE skill_invocation_event e
SET center_skill_id = r.skill_id
FROM resolved r
WHERE e.id = r.id AND e.center_skill_id IS DISTINCT FROM r.skill_id;
