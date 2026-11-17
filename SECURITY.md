# Security policy

## Supported versions

Security fixes go into the latest release. Please update to it before reporting.

| Version | Supported |
|---|---|
| 1.1.x | yes |
| < 1.1 | no |

## Reporting a vulnerability

Please **do not open a public issue** for a security problem.

Report it privately through GitHub instead: open the repository's **Security** tab and click
**Report a vulnerability**
([direct link](https://github.com/Yeongwonhan-Jeolmang/logdelta/security/advisories/new)).
Only the maintainer can see the report.

Please include:

- what an attacker could do, and under which conditions;
- the LogDelta version and how it was run (desktop app, command line, GitHub Action);
- steps or a small log file that reproduce the problem.

You can expect a first answer within a week. Once the problem is confirmed, a fix is prepared
privately and released, and the advisory is published with credit to you unless you prefer
otherwise.

## Scope

LogDelta reads log files, downloads URLs you give it, and writes reports. Problems worth reporting
include, for example:

- a crafted log, profile or session file that makes LogDelta run code, write files outside the
  chosen location, or exhaust memory far beyond the input size;
- HTML reports that run script from the compared logs;
- request headers or tokens given with `--header` or in the app ending up in reports, session
  files or error messages;
- the GitHub Action exposing secrets or running code from a pull request.

Regular expressions in your own profiles that are slow on your own logs are not security issues.