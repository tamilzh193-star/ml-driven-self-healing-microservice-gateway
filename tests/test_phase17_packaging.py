#!/usr/bin/env python3
"""
Phase 17 Automated Test Suite: Packaging & Deployment Automation
Verifies:
  1. Fat JAR artifact existence, file size (> 15MB), and Spring Boot manifest structure.
  2. Machine Learning service packaging (app.py, requirements.txt, trained model pickles).
  3. Single-click batch orchestration scripts (run_all.bat, stop_all.bat, check_health.bat).
  4. Cross-platform bash orchestration scripts (run_all.sh, stop_all.sh).
  5. Cluster health verification utility (scripts/verify_cluster.py).
"""

import os
import sys
import zipfile
import unittest

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(PROJECT_ROOT, "scripts"))
import verify_cluster

class TestPhase17PackagingSuite(unittest.TestCase):

    def test_01_gateway_fat_jar(self):
        jar_path = os.path.join(PROJECT_ROOT, "gateway", "target", "gateway-1.0.0.jar")
        self.assertTrue(os.path.exists(jar_path), f"Gateway JAR missing at {jar_path}")
        size_mb = os.path.getsize(jar_path) / (1024 * 1024)
        self.assertGreater(size_mb, 20.0, f"Gateway JAR size unexpectedly small: {size_mb:.2f} MB")

        # Verify Spring Boot executable JAR structure
        with zipfile.ZipFile(jar_path, "r") as z:
            namelist = z.namelist()
            self.assertTrue(any("BOOT-INF/classes/" in n for n in namelist), "Missing BOOT-INF/classes in Gateway JAR")
            self.assertTrue(any("BOOT-INF/lib/" in n for n in namelist), "Missing BOOT-INF/lib dependencies in Gateway JAR")
            self.assertIn("META-INF/MANIFEST.MF", namelist)

    def test_02_microservice_fat_jars(self):
        services = ["order-service", "payment-service", "inventory-service"]
        for svc in services:
            jar_path = os.path.join(PROJECT_ROOT, svc, "target", f"{svc}-1.0.0.jar")
            self.assertTrue(os.path.exists(jar_path), f"JAR missing for {svc} at {jar_path}")
            size_mb = os.path.getsize(jar_path) / (1024 * 1024)
            self.assertGreater(size_mb, 15.0, f"JAR size unexpectedly small for {svc}: {size_mb:.2f} MB")

            with zipfile.ZipFile(jar_path, "r") as z:
                namelist = z.namelist()
                self.assertTrue(any("BOOT-INF/classes/" in n for n in namelist), f"Missing BOOT-INF/classes in {svc} JAR")
                self.assertIn("META-INF/MANIFEST.MF", namelist)

    def test_03_ml_service_packaging(self):
        ml_dir = os.path.join(PROJECT_ROOT, "ml-service")
        self.assertTrue(os.path.exists(os.path.join(ml_dir, "app.py")), "ml-service/app.py missing")
        self.assertTrue(os.path.exists(os.path.join(ml_dir, "requirements.txt")), "ml-service/requirements.txt missing")

        with open(os.path.join(ml_dir, "requirements.txt"), "r", encoding="utf-8") as f:
            reqs = f.read().lower()
            self.assertIn("flask", reqs)
            self.assertIn("scikit-learn", reqs)

        model_dir = os.path.join(ml_dir, "model")
        self.assertTrue(os.path.exists(model_dir), "ml-service/model directory missing")
        model_files = os.listdir(model_dir)
        self.assertTrue(any(f.endswith(".pkl") or f.endswith(".joblib") for f in model_files),
                        "No serialized ML model files found in ml-service/model")

    def test_04_windows_orchestration_scripts(self):
        for script_name in ["run_all.bat", "stop_all.bat", "check_health.bat"]:
            path = os.path.join(PROJECT_ROOT, script_name)
            self.assertTrue(os.path.exists(path), f"Missing Windows script: {script_name}")
            with open(path, "r", encoding="utf-8", errors="ignore") as f:
                content = f.read()
                self.assertGreater(len(content), 50, f"Script {script_name} is empty or truncated")

    def test_05_unix_orchestration_scripts(self):
        for script_name in ["run_all.sh", "stop_all.sh"]:
            path = os.path.join(PROJECT_ROOT, script_name)
            self.assertTrue(os.path.exists(path), f"Missing Unix script: {script_name}")
            with open(path, "r", encoding="utf-8", errors="ignore") as f:
                content = f.read()
                self.assertTrue(content.startswith("#!/usr/bin/env bash"), f"{script_name} missing bash shebang")

    def test_06_verify_cluster_utility_structure(self):
        script_path = os.path.join(PROJECT_ROOT, "scripts", "verify_cluster.py")
        self.assertTrue(os.path.exists(script_path), "scripts/verify_cluster.py missing")

        # Verify service definitions
        self.assertEqual(len(verify_cluster.SERVICES), 5, "Expected 5 configured cluster services")
        ports = {s["port"] for s in verify_cluster.SERVICES}
        self.assertEqual(ports, {5000, 8080, 8081, 8082, 8083})

        # Test single service health check function format
        sample_res = verify_cluster.check_single_service(verify_cluster.SERVICES[0], timeout=0.5)
        self.assertIn("name", sample_res)
        self.assertIn("port", sample_res)
        self.assertIn("latency_ms", sample_res)
        self.assertIn("is_up", sample_res)

if __name__ == "__main__":
    unittest.main()
