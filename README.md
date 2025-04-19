Warcaroo
=======
<img src="roo.svg" align="right" width="200" height="200" alt="Kangaroo on a laptop">

Warcaroo is an experimental browser-based web crawler designed to archive web content into 
[WARC](https://en.wikipedia.org/wiki/WARC_(file_format)) files. Currently:

* Crawl state is stored in an SQLite database
* Uses per-host crawl queues
* Runs Chromium-based browsers locally or on multiple servers via SSH
* Web interface for inspecting progress and searching queues (not fully functional)
* REST API (OpenAPI documentation at /api and /scalar.html)
* Basic robots.txt support (currently at the page-level only)

Building
--------

Currently there are no releases so you need to build from source.

Install [OpenJDK 21 or newer](https://adoptium.net/) and [Apache Maven](https://maven.apache.org/).

    ArchLinux: pacman -S jdk21-openjdk maven
    Fedora/CentOS/RHEL: dnf install java-21-openjdk-devel maven 
    Ubuntu/Debian: apt install openjdk-21-jdk maven

Then build with:

    mvn package -DskipTests

This will produce a jar file at `crawler/target/warcaroo-$VERSION.jar` which you can run with `java -jar`.

Running a crawl
---------------

Running a basic crawl with the UI available on http://localhost:1234 looks like:

    java -jar crawler/target/warcaroo-0.1.0.jar -p 1234 https://example.com/

You'll need a Chromium-based browser installed.

Use --help to see the available options:

```
$ java -jar crawler/target/warcaroo-0.1.0.jar --help
Usage: warcaroo [URL...]
Options:
  -h, --help
      --host HOST          Host for web UI
  -j, --job-dir DIR        Directory for job data
  -p, --port PORT          Port for web UI
      --trace-cdp <file>   Write CDP trace to file
```

Running browsers remotely over SSH
----------------------------------

As browsing is CPU intensive it can be useful to the run the browser on a cluster of servers. Warcaroo can
launch browsers on remote servers via SSH using the `--ssh` option. The remote server does not need 
Warcaroo or Java installed, just the browser.

**Example:** Run 3 browsers: server1, server2 and local

```yaml
browsers:
  - workers: 4
  - shell: ['ssh', 'server1.example.org']
  - shell: ['ssh', 'server2.example.org']
```

**Example:** Run 4 workers on server1, 3 workers on server2 and no browser locally:

```yaml
browsers:
  - shell: ['ssh', 'server1.example.org']
    workers: 4
  - shell: ['ssh', 'server2.example.org']
    workers: 3
```

**Example:** Use `chromium` on server1 and `google-chrome-stable` locally:

```yaml
browsers:
  - executable: 'google-chrome-stable'
  - shell: ['ssh', 'server1.example.org']
    executable: chromium
```

**Example:** Use an SSH key file and port 2222 when connecting to server1

```yaml
browsers:
  - shell: ['ssh', '-i', 'id_rsa', '-p', '2222', 'server1.example.org']
```

You can even use SSH's SOCKS feature to proxy web requests back through the machine warcaroo is running on.
This can be useful if you want all the requests to come from a single IP address or if the remote servers do not have
direct internet access.

**Example:*** Tunnel web traffic through an SSH SOCKS proxy on port 1080

```yaml
browsers:
  - shell: ['ssh', '-R', '1080', 'server1.example.org']
    options: ['--proxy-server=socks://127.0.0.1:1080']
```