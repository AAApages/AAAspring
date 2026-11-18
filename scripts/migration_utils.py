#!/usr/bin/env python3
"""Shared migration helpers for Spring Boot + SQLite scripts."""

from __future__ import annotations

import os
import shutil
import signal
import socket
import subprocess
import time
from datetime import datetime
from pathlib import Path


class MigrationError(RuntimeError):
    """Raised when a migration step fails."""


def print_header(title: str) -> None:
    print("\n" + "=" * 60)
    print(title)
    print("=" * 60 + "\n")


def is_port_in_use(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        return sock.connect_ex(("localhost", port)) == 0


def ensure_port_not_in_use(port: int, hint: str) -> None:
    if is_port_in_use(port):
        raise MigrationError(f"Port {port} is already in use. {hint}")


class SqliteDatabaseFiles:
    """Manages SQLite database file backup and deletion."""

    def __init__(self, db_file: Path, backup_dir: Path):
        self.db_file = db_file
        self.backup_dir = backup_dir

    def backup(self) -> None:
        if not self.db_file.exists():
            print("No existing database file to backup")
            return

        self.backup_dir.mkdir(parents=True, exist_ok=True)
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        backup_file = self.backup_dir / f"sqlite_backup_{timestamp}.db"
        shutil.copy2(self.db_file, backup_file)
        print(f"Database backed up to: {backup_file}")

        for ext in ("-wal", "-shm"):
            src = Path(str(self.db_file) + ext)
            if src.exists():
                dst = self.backup_dir / f"sqlite_backup_{timestamp}.db{ext}"
                shutil.copy2(src, dst)
                print(f"{ext.lstrip('-').upper()} file backed up")

    def remove(self) -> None:
        print("\nRemoving old database...")
        for ext in ("", "-wal", "-shm"):
            target = Path(str(self.db_file) + ext)
            if target.exists():
                target.unlink()
        print("Old database removed")


class SkipModelInitFlag:
    """Manages the .skip-modelinit flag lifecycle."""

    def __init__(self, flag_file: Path):
        self.flag_file = flag_file

    def create(self) -> None:
        self.flag_file.parent.mkdir(parents=True, exist_ok=True)
        self.flag_file.touch()
        print(f"Created skip-modelinit flag at {self.flag_file}")

    def remove(self) -> None:
        if self.flag_file.exists():
            self.flag_file.unlink()
            print("Removed skip-modelinit flag")


class SpringBootSchemaRunner:
    """Runs Spring Boot temporarily to create schema and then shuts it down."""

    def __init__(
        self,
        project_root: Path,
        log_file: str,
        spring_port: int,
        additional_run_args: list[str] | None = None,
    ):
        self.project_root = project_root
        self.log_file = log_file
        self.spring_port = spring_port
        self.additional_run_args = additional_run_args or []

    def run(self, timeout_seconds: int = 180, settle_seconds: int = 5) -> None:
        process = self._start()
        try:
            self._wait_for_start(process, timeout_seconds, settle_seconds)
        finally:
            self._stop(process)

    def _start(self) -> subprocess.Popen:
        run_args = ["--spring.jpa.hibernate.ddl-auto=create", *self.additional_run_args]
        run_args_value = " ".join(run_args)

        return subprocess.Popen(
            [
                "./mvnw",
                "spring-boot:run",
                f"-Dspring-boot.run.arguments={run_args_value}",
            ],
            stdout=open(self.log_file, "w"),
            stderr=subprocess.STDOUT,
            cwd=self.project_root,
            preexec_fn=os.setsid,
        )

    def _wait_for_start(
        self, process: subprocess.Popen, timeout_seconds: int, settle_seconds: int
    ) -> None:
        print("Waiting for Spring Boot", end="", flush=True)
        start_time = time.time()

        while time.time() - start_time < timeout_seconds:
            if process.poll() is not None:
                raise MigrationError(
                    f"Application exited with status {process.returncode} before port "
                    f"{self.spring_port} became available. Check {self.log_file} for errors"
                )
            if is_port_in_use(self.spring_port):
                print(" OK")
                time.sleep(settle_seconds)
                return
            print(".", end="", flush=True)
            time.sleep(1)

        print("\nTimeout waiting for Spring Boot to start")
        raise MigrationError(f"Application failed to start. Check {self.log_file} for errors")

    def _stop(self, process: subprocess.Popen) -> None:
        print("\nStopping temporary instance...")
        try:
            os.killpg(os.getpgid(process.pid), signal.SIGTERM)
            process.wait(timeout=10)
        except ProcessLookupError:
            process.wait()
            return
        except subprocess.TimeoutExpired:
            os.killpg(os.getpgid(process.pid), signal.SIGKILL)
            process.wait()
        time.sleep(2)
        print("Temporary instance stopped")
