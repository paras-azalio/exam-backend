-- Run this once to create the database and tables.
-- Hibernate (ddl-auto=update) will also add new columns automatically on startup.

CREATE DATABASE IF NOT EXISTS exam_db
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE exam_db;

-- exam_sessions has been merged into exam_results.
-- The fields session_key (unique identifier) and started_at (exam start time)
-- that were previously in exam_sessions now live directly in exam_results.

CREATE TABLE IF NOT EXISTS exam_results (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_key  VARCHAR(300) NOT NULL UNIQUE,
  student_name VARCHAR(255),
  exam_code    VARCHAR(50),
  exam_title   VARCHAR(500),
  score        DOUBLE,
  total_marks  DOUBLE,
  grade        VARCHAR(10),
  pdf_path     VARCHAR(500),
  started_at   DATETIME,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_result_session (session_key),
  INDEX idx_result_exam    (exam_code)
);
