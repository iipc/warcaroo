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

You'll need a Chromium-based browser installed. Warcaroo will try to find it automatically, but you can
use the `--browser` option to specify the path to the browser executable if needed.

Use --help to see the other available options:

```
$ java -jar crawler/target/warcaroo-0.1.0.jar --help
Usage: warcaroo [options] seed-url...

Options:
--block REGEX             Block fetching of resources that match the specified REGEX pattern.
--browser PATH            Set the path to the browser executable
--browser-options OPTIONS Additional command-line options to pass to the browser.
--crawl-delay MILLIS      Wait this long before crawling another page from the same queue.
--include REGEX           Include pages that match the specified REGEX pattern in the crawl scope.
--headless                Run the browser in headless mode.
--host HOST               Set the hostname to bind the server.
--port PORT               Set the port to bind the server.
--seed-file FILE          Load seed URLs from the specified file.
--ssh HOST                Run a browser on a remote server over SSH.
--ssh-command COMMAND     Set the ssh command to use, including SSH command-line options.
--trace-cdp               Enables detailed logging of messages to and from the browser.
-A, --user-agent STR      Set the User-Agent string to identify the crawler to the server.
--warc-prefix STR         Prefix used when naming WARC files.
-w, --workers INT         Specify the number of browser windows to use per browser (default is 1).
-v, --verbose             Increase verbosity of the output.

Examples:
warcaroo --include "https?://([^/]+\.)example\.com/.*" -A "MyCrawler/1.0" -w 5
```

Running browsers remotely over SSH
----------------------------------

As browsing is CPU intensive it can be useful to the run the browser on a cluster of servers. Warcaroo can
launch browsers on remote servers via SSH using the `--ssh` option. The remote server does not need 
Warcaroo or Java installed, just the browser.

**Example:** Run 3 browsers: server1, server2 and local

    java -jar warcaroo.jar --ssh server1.example.org --ssh server2.example.org

When using the `--ssh` option, the `--browser`, `--browser-options`, `--ssh-command`, `--workers`  options
apply to the `--ssh` option that follows them. To set `--browser` or `--workers` for the local browser put 
them at the end of the command-line.

**Example:** Run 4 workers on server1, 3 workers on server2 and no browser locally:

    java -jar warcaroo.jar --workers 4 --ssh server1.example.org --workers 3 --ssh server2.example.org --workers 0

**Example:** Use `chromium` on server1 and `google-chrome-stable` locally:

    java -jar warcaroo.jar --browser chromium --ssh server1.example.org --browser google-chrome-stable

You can set custom SSH options using `--ssh-command`:

**Example:** Use an SSH key file and port 2222 when connecting to server1

    java -jar warcaroo.jar --ssh-command 'ssh -i id_rsa -p 2222' --ssh server1.example.org

You can even use SSH's SOCKS feature to proxy web requests back through the machine warcaroo is running on.
This can be useful if you want all the requests to come from a single IP address or if the remote servers do not have
direct internet access.

**Example:*** Tunnel web traffic through an SSH SOCKS proxy on port 1080

    java -jar warcaroo.jar --browser-options '--proxy-server=socks://127.0.0.1:1080' --ssh-command 'ssh -R 1080' --ssh server1.example.org
