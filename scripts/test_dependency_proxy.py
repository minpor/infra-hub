import importlib.util
from pathlib import Path
import socket
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("proxy", Path(__file__).resolve().parents[1] / "deploy/runner/dependency-proxy.py")
proxy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proxy)


class DependencyProxyTest(unittest.TestCase):
    def test_only_explicit_https_hosts(self):
        for host in ("127.0.0.1:443", "169.254.169.254:443", "repo.maven.apache.org:80", "repo.maven.apache.org.evil.test:443", "example.com:443", "[::1]:443"):
            with self.assertRaises(ValueError):
                proxy.destination(host)

    def test_dns_private_and_mixed_answers_are_rejected(self):
        for addresses in (("127.0.0.1",), ("10.0.0.1",), ("169.254.169.254",), ("8.8.8.8", "192.168.1.1")):
            with patch.object(socket, "getaddrinfo", return_value=[(socket.AF_INET, socket.SOCK_STREAM, 6, "", (ip, 443)) for ip in addresses]):
                with self.assertRaises(ValueError):
                    proxy.destination("repo.maven.apache.org:443")

    def test_returned_address_is_validated_not_resolved_twice(self):
        address = [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("8.8.8.8", 443))]
        with patch.object(socket, "getaddrinfo", return_value=address) as resolve:
            self.assertEqual(proxy.destination("repo.maven.apache.org:443"), address)
            resolve.assert_called_once()


if __name__ == "__main__":
    unittest.main()
