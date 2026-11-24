// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.cli.command;

import io.github.logdelta.cli.CliContext;
import io.github.logdelta.cli.ExitCode;
import io.github.logdelta.cli.args.ArgParser;
import io.github.logdelta.cli.args.OptionSpec;
import io.github.logdelta.cli.args.ParsedArgs;
import io.github.logdelta.cli.args.UsageException;
import io.github.logdelta.core.normalize.BuiltinRules;
import io.github.logdelta.core.normalize.Rule;
import io.github.logdelta.core.preset.Preset;
import io.github.logdelta.core.preset.Presets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/// `logdelta completion SHELL`: prints a tab-completion script for bash, zsh, fish or PowerShell.
///
/// The scripts are generated from the option declarations of every command, so they never fall
/// behind the CLI. They complete for both names LogDelta is installed under: `logdelta` (the
/// jar or an alias) and `logdelta-cli` (the launcher of the native installers).
public final class CompletionCommand implements Command {

    /// Program names the scripts register for.
    static final List<String> PROGRAMS = List.of("logdelta", "logdelta-cli");
    /// Supported shells.
    static final List<String> SHELLS = List.of("bash", "zsh", "fish", "powershell");

    private final Supplier<List<Command>> commands;

    /// @param commands every command of the CLI, including this one
    public CompletionCommand(Supplier<List<Command>> commands) {
        this.commands = commands;
    }

    @Override
    public String name() {
        return "completion";
    }

    @Override
    public String summary() {
        return "print a shell completion script (bash, zsh, fish or powershell)";
    }

    @Override
    public String usage() {
        return "completion bash|zsh|fish|powershell";
    }

    @Override
    public String details() {
        return """
                Installing:
                  bash        logdelta completion bash > ~/.local/share/bash-completion/completions/logdelta
                  zsh         logdelta completion zsh > "${fpath[1]}/_logdelta"   (then restart zsh)
                  fish        logdelta completion fish > ~/.config/fish/completions/logdelta.fish
                  PowerShell  logdelta completion powershell | Out-String | Invoke-Expression
                              (add that line to $PROFILE to keep it)
                The native installers already include these scripts (see docs/INSTALLING.md).
                """;
    }

    @Override
    public ArgParser parser() {
        return new ArgParser().add(OptionSpec.flag("help", 'h', "show this help"));
    }

    @Override
    public int execute(ParsedArgs args, CliContext ctx) throws Exception {
        if (args.positionals().size() != 1) {
            throw new UsageException("Name one shell: " + String.join(", ", SHELLS));
        }
        String shell = args.positionals().getFirst().toLowerCase(Locale.ROOT);
        String script = switch (shell) {
            case "bash" -> bash();
            case "zsh" -> zsh();
            case "fish" -> fish();
            case "powershell", "pwsh" -> powershell();
            default -> throw new UsageException("Unknown shell '" + shell + "'; use " + String.join(", ", SHELLS));
        };
        ctx.out().print(script);
        ctx.out().flush();
        return ExitCode.OK;
    }

    // ------------------------------------------------------------------------------------------

    /// Fixed values an option accepts, for completion; `null` means "complete file names".
    static List<String> choices(String command, String option) {
        return switch (option) {
            case "format" -> command.equals("check") ? List.of("summary", "markdown", "json", "junit")
                    : List.of("side-by-side", "unified", "summary", "html", "json", "markdown", "junit");
            case "log-format" -> List.of("auto", "plain", "json", "logfmt");
            case "preset" -> {
                List<String> v = new ArrayList<>(List.of("auto", "none"));
                Presets.all().stream().map(Preset::id).forEach(v::add);
                yield v;
            }
            case "color" -> List.of("auto", "always", "never");
            case "exit-code" -> List.of("any", "problems", "never");
            case "algorithm" -> List.of("myers", "patience");
            case "fail-on" -> List.of("problems", "errors", "changes");
            case "encoding" -> List.of("auto", "UTF-8", "ISO-8859-1", "UTF-16LE", "UTF-16BE", "windows-1252");
            case "enable", "disable" -> {
                List<String> v = new ArrayList<>(List.of("all"));
                BuiltinRules.all().stream().map(Rule::id).forEach(v::add);
                Presets.all().forEach(p -> p.rules().forEach(r -> v.add(r.id())));
                yield v;
            }
            default -> null;
        };
    }

    /// Whether an option's value is free text rather than a path (no file completion).
    static boolean freeText(String option) {
        return switch (option) {
            case "mask", "ignore", "ignore-field", "compare-field", "since", "until", "redact", "header", "timeout",
                 "context", "max-hunks", "width", "keep", "include", "exclude" -> true;
            default -> false;
        };
    }

    private Map<String, List<OptionSpec>> optionsByCommand() {
        Map<String, List<OptionSpec>> out = new LinkedHashMap<>();
        for (Command c : commands.get()) {
            out.put(c.name(), c.parser().specs());
        }
        return out;
    }

    private String commandNames() {
        List<String> names = new ArrayList<>();
        commands.get().forEach(c -> names.add(c.name()));
        names.add("help");
        return String.join(" ", names);
    }

    String bash() {
        StringBuilder sb = new StringBuilder();
        sb.append("# bash completion for LogDelta. Generated by 'logdelta completion bash'.\n");
        sb.append("_logdelta() {\n");
        sb.append("    local cur prev cmd i\n");
        sb.append("    cur=\"${COMP_WORDS[COMP_CWORD]}\"\n");
        sb.append("    prev=\"${COMP_WORDS[COMP_CWORD-1]}\"\n");
        sb.append("    cmd=diff\n");
        sb.append("    for ((i = 1; i < COMP_CWORD; i++)); do\n");
        sb.append("        case \"${COMP_WORDS[i]}\" in\n");
        sb.append("            ").append(commandNames().replace(' ', '|')).append(") cmd=\"${COMP_WORDS[i]}\"; break ;;\n");
        sb.append("            -*) ;;\n");
        sb.append("            *) break ;;\n");
        sb.append("        esac\n");
        sb.append("    done\n");
        sb.append("    if [[ $COMP_CWORD -eq 1 && \"$cur\" != -* ]]; then\n");
        sb.append("        COMPREPLY=($(compgen -W \"").append(commandNames()).append("\" -- \"$cur\") $(compgen -f -- \"$cur\"))\n");
        sb.append("        return\n");
        sb.append("    fi\n");
        sb.append("    case \"$cmd\" in\n");
        for (Map.Entry<String, List<OptionSpec>> e : optionsByCommand().entrySet()) {
            String cmd = e.getKey();
            sb.append("        ").append(cmd).append(")\n");
            sb.append("            case \"$prev\" in\n");
            for (OptionSpec o : e.getValue()) {
                if (!o.takesValue()) {
                    continue;
                }
                sb.append("                ").append(flags(o, "|")).append(")\n");
                List<String> ch = choices(cmd, o.longName());
                if (ch != null) {
                    sb.append("                    COMPREPLY=($(compgen -W \"").append(String.join(" ", ch))
                            .append("\" -- \"$cur\")); return ;;\n");
                } else if (freeText(o.longName())) {
                    sb.append("                    COMPREPLY=(); return ;;\n");
                } else {
                    sb.append("                    COMPREPLY=($(compgen -f -- \"$cur\")); return ;;\n");
                }
            }
            sb.append("            esac\n");
            if (cmd.equals("completion")) {
                sb.append("            COMPREPLY=($(compgen -W \"").append(String.join(" ", SHELLS))
                        .append(" --help\" -- \"$cur\")); return ;;\n");
                continue;
            }
            sb.append("            if [[ \"$cur\" == -* ]]; then\n");
            sb.append("                COMPREPLY=($(compgen -W \"");
            List<String> all = new ArrayList<>();
            for (OptionSpec o : e.getValue()) {
                all.add("--" + o.longName());
                if (o.shortName() != 0) {
                    all.add("-" + o.shortName());
                }
            }
            sb.append(String.join(" ", all)).append("\" -- \"$cur\"))\n");
            sb.append("            else\n");
            sb.append("                COMPREPLY=($(compgen -f -- \"$cur\"))\n");
            sb.append("            fi\n");
            sb.append("            ;;\n");
        }
        sb.append("        help) COMPREPLY=($(compgen -W \"").append(commandNames()).append("\" -- \"$cur\")) ;;\n");
        sb.append("    esac\n");
        sb.append("}\n");
        sb.append("complete -o filenames -o bashdefault -F _logdelta ").append(String.join(" ", PROGRAMS)).append('\n');
        return sb.toString();
    }

    String zsh() {
        StringBuilder sb = new StringBuilder();
        sb.append("#compdef ").append(String.join(" ", PROGRAMS)).append('\n');
        sb.append("# zsh completion for LogDelta. Generated by 'logdelta completion zsh'.\n\n");
        sb.append("_logdelta() {\n");
        sb.append("    local -a commands\n");
        sb.append("    commands=(\n");
        for (Command c : commands.get()) {
            sb.append("        '").append(c.name()).append(':').append(zshEscape(c.summary())).append("'\n");
        }
        sb.append("        'help:show help for a command'\n");
        sb.append("    )\n");
        sb.append("    local cmd=diff\n");
        sb.append("    if (( CURRENT > 2 )) && (( ${commands[(I)${words[2]}:*]} )); then\n");
        sb.append("        cmd=${words[2]}\n");
        sb.append("        shift words\n");
        sb.append("        (( CURRENT-- ))\n");
        sb.append("    elif (( CURRENT == 2 )) && [[ ${words[2]} != -* ]]; then\n");
        sb.append("        _describe -t commands 'logdelta command' commands\n");
        sb.append("        _files\n");
        sb.append("        return\n");
        sb.append("    fi\n");
        sb.append("    case $cmd in\n");
        for (Map.Entry<String, List<OptionSpec>> e : optionsByCommand().entrySet()) {
            String cmd = e.getKey();
            sb.append("        ").append(cmd).append(")\n");
            sb.append("            _arguments -s -S \\\n");
            for (OptionSpec o : e.getValue()) {
                sb.append("                ").append(zshSpec(cmd, o)).append(" \\\n");
            }
            if (cmd.equals("completion")) {
                sb.append("                '1:shell:(").append(String.join(" ", SHELLS)).append(")'\n");
            } else {
                sb.append("                '*:file:_files'\n");
            }
            sb.append("            ;;\n");
        }
        sb.append("        help) _describe -t commands 'logdelta command' commands ;;\n");
        sb.append("    esac\n");
        sb.append("}\n\n");
        sb.append("_logdelta \"$@\"\n");
        return sb.toString();
    }

    private static String zshSpec(String cmd, OptionSpec o) {
        String help = zshEscape(o.help());
        String names = o.shortName() != 0 ? "{-" + o.shortName() + ",--" + o.longName() + "}" : "--" + o.longName();
        String prefix = o.repeatable() ? "'*'"
                : o.shortName() != 0 ? "'(-" + o.shortName() + " --" + o.longName() + ")'" : "";
        StringBuilder spec = new StringBuilder(prefix).append(names).append("'[").append(help).append(']');
        if (o.takesValue()) {
            List<String> ch = choices(cmd, o.longName());
            String action = ch != null ? "(" + String.join(" ", ch) + ")" : freeText(o.longName()) ? " " : "_files";
            spec.append(':').append(zshEscape(o.argName())).append(':').append(action);
        }
        return spec.append("'").toString();
    }

    private static String zshEscape(String s) {
        return s.replace("'", "'\\''").replace("[", "\\[").replace("]", "\\]").replace(":", "\\:");
    }

    String fish() {
        StringBuilder sb = new StringBuilder();
        sb.append("# fish completion for LogDelta. Generated by 'logdelta completion fish'.\n");
        List<String> names = new ArrayList<>();
        commands.get().forEach(c -> names.add(c.name()));
        String all = String.join(" ", names) + " help";
        for (String program : PROGRAMS) {
            sb.append("\n# ").append(program).append('\n');
            sb.append("complete -c ").append(program).append(" -f\n");
            for (Command c : commands.get()) {
                sb.append("complete -c ").append(program).append(" -n '__fish_use_subcommand' -a ").append(c.name())
                        .append(" -d '").append(fishEscape(c.summary())).append("'\n");
            }
            sb.append("complete -c ").append(program).append(" -n '__fish_use_subcommand' -a help -d 'show help'\n");
            for (Map.Entry<String, List<OptionSpec>> e : optionsByCommand().entrySet()) {
                String cmd = e.getKey();
                String condition = cmd.equals("diff")
                        ? "not __fish_seen_subcommand_from " + all.replace("diff ", "") + "; or __fish_seen_subcommand_from diff"
                        : "__fish_seen_subcommand_from " + cmd;
                if (cmd.equals("completion")) {
                    sb.append("complete -c ").append(program).append(" -n '").append(condition).append("' -a '")
                            .append(String.join(" ", SHELLS)).append("'\n");
                }
                for (OptionSpec o : e.getValue()) {
                    sb.append("complete -c ").append(program).append(" -n '").append(condition).append("'");
                    if (o.shortName() != 0) {
                        sb.append(" -s ").append(o.shortName());
                    }
                    sb.append(" -l ").append(o.longName());
                    if (o.takesValue()) {
                        List<String> ch = choices(cmd, o.longName());
                        if (ch != null) {
                            sb.append(" -x -a '").append(String.join(" ", ch)).append("'");
                        } else if (freeText(o.longName())) {
                            sb.append(" -x");
                        } else {
                            sb.append(" -r -F");
                        }
                    }
                    sb.append(" -d '").append(fishEscape(o.help())).append("'\n");
                }
                if (!cmd.equals("completion") && !cmd.equals("rules")) {
                    sb.append("complete -c ").append(program).append(" -n '").append(condition).append("' -F\n");
                }
            }
        }
        return sb.toString();
    }

    private static String fishEscape(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    String powershell() {
        StringBuilder sb = new StringBuilder();
        sb.append("# PowerShell completion for LogDelta. Generated by 'logdelta completion powershell'.\n");
        sb.append("$logdeltaCommands = @{\n");
        for (Map.Entry<String, List<OptionSpec>> e : optionsByCommand().entrySet()) {
            sb.append("    '").append(e.getKey()).append("' = @(");
            List<String> opts = new ArrayList<>();
            for (OptionSpec o : e.getValue()) {
                opts.add("'--" + o.longName() + "'");
                if (o.shortName() != 0) {
                    opts.add("'-" + o.shortName() + "'");
                }
            }
            sb.append(String.join(", ", opts)).append(")\n");
        }
        sb.append("}\n");
        sb.append("$logdeltaValues = @{\n");
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (Map.Entry<String, List<OptionSpec>> e : optionsByCommand().entrySet()) {
            for (OptionSpec o : e.getValue()) {
                List<String> ch = choices(e.getKey(), o.longName());
                if (ch != null) {
                    values.put(e.getKey() + ":--" + o.longName(), ch);
                    if (o.shortName() != 0) {
                        values.put(e.getKey() + ":-" + o.shortName(), ch);
                    }
                }
            }
        }
        for (Map.Entry<String, List<String>> v : values.entrySet()) {
            sb.append("    '").append(v.getKey()).append("' = @(");
            List<String> quoted = v.getValue().stream().map(x -> "'" + x + "'").toList();
            sb.append(String.join(", ", quoted)).append(")\n");
        }
        sb.append("}\n");
        sb.append("""
                $logdeltaShells = @('bash', 'zsh', 'fish', 'powershell')
                Register-ArgumentCompleter -Native -CommandName %s -ScriptBlock {
                    param($wordToComplete, $commandAst, $cursorPosition)
                    $words = @($commandAst.CommandElements | ForEach-Object { $_.ToString() })
                    $cmd = 'diff'
                    $start = 1
                    if ($words.Count -gt 1 -and $logdeltaCommands.ContainsKey($words[1])) { $cmd = $words[1]; $start = 2 }
                    $prev = if ($wordToComplete -eq '') { $words[-1] } else { $words[-2] }
                    $key = "${cmd}:$prev"
                    if ($logdeltaValues.ContainsKey($key)) {
                        $logdeltaValues[$key] | Where-Object { $_ -like "$wordToComplete*" } |
                            ForEach-Object { [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_) }
                        return
                    }
                    if ($cmd -eq 'completion') {
                        $logdeltaShells | Where-Object { $_ -like "$wordToComplete*" } |
                            ForEach-Object { [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_) }
                        return
                    }
                    if ($wordToComplete -like '-*') {
                        $logdeltaCommands[$cmd] | Where-Object { $_ -like "$wordToComplete*" } |
                            ForEach-Object { [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterName', $_) }
                        return
                    }
                    if ($words.Count -le 2 -and $start -eq 1) {
                        @($logdeltaCommands.Keys) + 'help' | Where-Object { $_ -like "$wordToComplete*" } |
                            ForEach-Object { [System.Management.Automation.CompletionResult]::new($_, $_, 'Command', $_) }
                    }
                    Get-ChildItem -Path "$wordToComplete*" -ErrorAction SilentlyContinue |
                        ForEach-Object { [System.Management.Automation.CompletionResult]::new($_.Name, $_.Name, 'ProviderItem', $_.FullName) }
                }
                """.formatted(String.join(", ", PROGRAMS)));
        return sb.toString();
    }

    private static String flags(OptionSpec o, String sep) {
        return o.shortName() != 0 ? "-" + o.shortName() + sep + "--" + o.longName() : "--" + o.longName();
    }
}
