CREATE TABLE IF NOT EXISTS occupancy_history (
    gym_id TEXT,
    location TEXT,
    operator_id TEXT,
    day TEXT,
    hour TEXT,
    occupancy INTEGER,
    PRIMARY KEY (gym_id, location, day, hour)
)