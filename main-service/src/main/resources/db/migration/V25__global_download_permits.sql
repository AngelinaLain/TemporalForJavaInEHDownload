CREATE TABLE eh_download_permit_lock (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO eh_download_permit_lock(id) VALUES(1);
CREATE TABLE eh_download_permit (
 gid BIGINT NOT NULL PRIMARY KEY,
 workflow_id VARCHAR(200) NOT NULL,
 run_id VARCHAR(100) NOT NULL,
 active BOOLEAN NOT NULL DEFAULT FALSE,
 created_at BIGINT NOT NULL,
 UNIQUE KEY uk_download_run(workflow_id, run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
