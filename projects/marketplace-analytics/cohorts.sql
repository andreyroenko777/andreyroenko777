WITH first_orders AS (
    SELECT customer_id, min(substr(order_date, 1, 7)) AS cohort
    FROM orders
    GROUP BY customer_id
), activity AS (
    SELECT DISTINCT customer_id, substr(order_date, 1, 7) AS month
    FROM orders
), cohort_sizes AS (
    SELECT cohort, count(*) AS customers FROM first_orders GROUP BY cohort
)
SELECT first_orders.cohort, activity.month, cohort_sizes.customers,
       count(*) AS active_customers,
       round(100.0 * count(*) / cohort_sizes.customers, 1) AS retention_pct
FROM first_orders
JOIN activity USING (customer_id)
JOIN cohort_sizes USING (cohort)
GROUP BY first_orders.cohort, activity.month, cohort_sizes.customers
ORDER BY first_orders.cohort, activity.month;
