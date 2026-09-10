import subprocess
import unittest
from unittest.mock import patch
from device import Device, APP_ID


class DeviceTest(unittest.TestCase):
    def test_missing_app_is_a_valid_first_install_state(self):
        device = Device.__new__(Device)
        device._serial = "SYNTHETIC_DEVICE"
        with patch("device.subprocess.run", return_value=subprocess.CompletedProcess([], 1, "", "")):
            self.assertEqual("", device.run("shell", "pm", "path", APP_ID, allow_missing_package=True))

    def test_other_adb_failure_is_not_ignored(self):
        device = Device.__new__(Device)
        device._serial = "SYNTHETIC_DEVICE"
        with patch("device.subprocess.run", return_value=subprocess.CompletedProcess([], 1, "", "private-like diagnostic")):
            with self.assertRaises(RuntimeError) as error:
                device.run("pull", "synthetic", "synthetic", allow_missing_package=True)
            self.assertNotIn("SYNTHETIC_DEVICE", str(error.exception))
            self.assertNotIn("private-like", str(error.exception))

    def test_multiple_or_unauthorized_devices_are_rejected(self):
        for output in ("List of devices attached\n", "List of devices attached\nSYNTHETIC unauthorized\n",
                       "List of devices attached\nSYNTHETIC_A device\nSYNTHETIC_B device\n"):
            with patch("device.subprocess.run", return_value=subprocess.CompletedProcess([], 0, output, "")):
                with self.assertRaises(RuntimeError):
                    Device()

    def test_exactly_one_authorized_device_is_selected_in_memory(self):
        with patch("device.subprocess.run", return_value=subprocess.CompletedProcess([], 0, "List of devices attached\nSYNTHETIC device\n", "")):
            self.assertEqual("SYNTHETIC", Device()._serial)


if __name__ == "__main__":
    unittest.main()
