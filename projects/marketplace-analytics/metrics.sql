WITH monthly AS (
    SELECT substr(order_date, 1, 7) AS month, channel,
           count(*) AS orders,
           sum(CASE WHEN returned = 0 THEN gross ELSE 0 END) AS net_revenue,
           round(100.0 * avg(returned), 1) AS return_pct
    FROM orders
    GROUP BY 1, 2
), previous AS (
    SELECT *, lag(net_revenue) OVER (PARTITION BY channel ORDER BY month) AS previous_revenue
    FROM monthly
)
SELECT month, channel, orders, net_revenue, return_pct,
       round(100.0 * (net_revenue - previous_revenue) / nullif(previous_revenue, 0), 1) AS mom_pct
FROM previous
ORDER BY month, channel;
