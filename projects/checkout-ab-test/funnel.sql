WITH first_events AS (
    SELECT a.user_id, a.variant, min(e.event_time) AS opened_at
    FROM assignments AS a
    LEFT JOIN events AS e ON e.user_id = a.user_id AND e.event = 'checkout_opened'
    GROUP BY a.user_id, a.variant
), details AS (
    SELECT f.user_id, f.variant, f.opened_at, min(e.event_time) AS submitted_at
    FROM first_events AS f
    LEFT JOIN events AS e ON e.user_id = f.user_id AND e.event = 'details_submitted'
        AND e.event_time > f.opened_at
    GROUP BY f.user_id, f.variant, f.opened_at
)
SELECT d.user_id, d.variant, d.opened_at IS NOT NULL AS opened,
       d.submitted_at IS NOT NULL AS submitted,
       count(e.user_id) > 0 AS paid
FROM details AS d
LEFT JOIN events AS e ON e.user_id = d.user_id AND e.event = 'paid'
    AND e.event_time > d.submitted_at
GROUP BY d.user_id, d.variant, d.opened_at, d.submitted_at
ORDER BY d.user_id;
