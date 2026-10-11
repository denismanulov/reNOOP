package com.noop.friends

/**
 * The stdout, verbatim, of the Swift twins compiled standalone and run over a spread of inputs
 * (`swiftc -O FriendsWire.swift FriendsServerAddress.swift main.swift -o t && ./t`, Swift 6.3.3 on
 * macOS 27 arm64). `FriendsWire.swift` is the package source as it stands; `FriendsServerAddress.swift`
 * is that enum cut out of `Strand/Friends/FriendsAPI.swift`, which needs nothing but Foundation.
 *
 * Each line is `<input> => <answer>`, with `nil` for no answer. Both sides are escaped so a line holds
 * any text: `\\` `\n` `\r` `\t`, `\u{HEX}` for a code point outside printable ASCII or a dollar sign, and
 * `\u{20}` for a space that begins or ends the text. The sections, in order:
 *
 *   normalized     `FriendsInviteCode.normalized(input)`
 *   extract        `FriendsInviteCode.extract(input)`
 *   display        `FriendsInviteCode.display(input)`
 *   pageLink       `FriendsInviteCode.pageLink(server:code:)` for "server code"
 *   signingString  `FriendsWire.signingString` for "method target time nonce bodyHash"
 *   strapHandle    two lines an id: `FriendsWire.strapHandleInput(adoptedId:)`, then the lower-case hex
 *                  SHA-256 of its UTF-8 bytes, which is the handle
 *   sha256         lower-case hex SHA-256 of the input's UTF-8 bytes
 *   indented       `FriendsWire.indented(input)`
 *   address        `FriendsServerAddress.validate(input)`: "ok <address>" or "refused <problem>"
 *
 * The tests recompute every line from its own left side, so nothing here is a copy of a Kotlin answer.
 */
internal object FriendsWireOracle {
    const val STDOUT = """# normalized
\u{20}O12-3456 789\u{20} => 0123456789
ilooabcdef => 1100ABCDEF
K7QM2\r\nXRD4P => K7QM2XRD4P
ABCDE-1234 => nil
ABCDE-1234U => nil
 => nil
K7QM2XRD\u{DF} => K7QM2XRDSS
K7QM2XRD4\u{DF} => nil
k7qm2-xrd4p => K7QM2XRD4P
K7QM2XRD4P => K7QM2XRD4P
K7QM2\tXRD4P => K7QM2XRD4P
K7QM2\u{A0}XRD4P => nil
K7QM2\u{2013}XRD4P => nil
K7QM2\u{2014}XRD4P => nil
\u{FF4B}\u{FF17}\u{FF51}\u{FF4D}\u{FF12}\u{FF58}\u{FF52}\u{FF44}\u{FF14}\u{FF50} => nil
k7qm2xrd4\u{131} => K7QM2XRD41
k7qm2xrd4\u{130} => nil
k7qm2xrd4i\u{307} => nil
K7QM2XRD\u{FB01} => K7QM2XRDF1
K7QM2XR\u{FB03} => K7QM2XRFF1
K7QM2XRD4\u{17F} => K7QM2XRD4S
K7QM2XRD4\u{212A} => nil
K7QM2XRD\u{149} => nil
K7QM2XRD4\u{1C6} => nil
K7QM2XR\u{390} => nil
O0O0O0O0O0 => 0000000000
oooooooooo => 0000000000
LLLLLLLLLL => 1111111111
IIIIIIIIII => 1111111111
lllllIIIII => 1111111111
UUUUUUUUUU => nil
\n\n K7QM2 - XRD4P \r\n => K7QM2XRD4P
K7QM2--XRD4P => K7QM2XRD4P
-K7QM2XRD4P- => K7QM2XRD4P
K 7 Q M 2 X R D 4 P => K7QM2XRD4P
K7QM2XRD4PP => nil
K7QM2XRD4 => nil
K7QM2\u{B}XRD4P => nil
K7QM2\u{C}XRD4P => nil
\u{FEFF}K7QM2XRD4P => nil
K7QM2XRD4P\u{0} => nil
K7QM2XRD4\u{1F600} => nil
K7QM2XRD4\u{C9} => nil
K7QM2XRD4E\u{301} => nil
K7QM2_XRD4P => nil
K7QM2.XRD4P => nil
K7QM2/XRD4P => nil
k7qm2\u{2003}xrd4p => nil
k7qm2\u{2028}xrd4p => nil
\u{41A}7QM2XRD4P => nil
K7QM2XRD4\u{440} => nil
0123456789 => 0123456789
abcdefghjk => ABCDEFGHJK
mnpqrstvwx => MNPQRSTVWX
yz01234567 => YZ01234567
---------- => nil
\u{20} \u{20} => nil
# extract
\u{20}k7qm2 xrd4p\u{20} => K7QM2XRD4P
https://renoop.duckdns.org/i/K7QM2-XRD4P => K7QM2XRD4P
https://example.org/friends/i/k7qm2-xrd4p => K7QM2XRD4P
renoop://friends/add?c=k7qm2-xrd4p => K7QM2XRD4P
https://example.org/other/K7QM2-XRD4P => nil
renoop://today => nil
renoop://friends/add?c=nope => nil
hello => nil
Add me on reNOOP Friends: https://renoop.duckdns.org/i/K7QM2-XRD4P\nOr enter the code K7QM2-XRD4P on the Friends tab. => K7QM2XRD4P
\u{414}\u{43E}\u{431}\u{430}\u{432}\u{44C} \u{43C}\u{435}\u{43D}\u{44F} \u{432} \u{434}\u{440}\u{443}\u{437}\u{44C}\u{44F} \u{432} reNOOP: https://renoop.duckdns.org/i/K7QM2-XRD4P\n\u{418}\u{43B}\u{438} \u{432}\u{432}\u{435}\u{434}\u{438} \u{43A}\u{43E}\u{434} K7QM2-XRD4P \u{43D}\u{430} \u{432}\u{43A}\u{43B}\u{430}\u{434}\u{43A}\u{435} \u{AB}\u{414}\u{440}\u{443}\u{437}\u{44C}\u{44F}\u{BB}. => K7QM2XRD4P
https://renoop.duckdns.org/i/K7QM2-XRD4P. => K7QM2XRD4P
see https://renoop.duckdns.org/i/K7QM2-XRD4P), thanks! => K7QM2XRD4P
my code is k7qm2-xrd4p. => K7QM2XRD4P
code AAAAA-BBBBB or https://example.org/i/K7QM2-XRD4P => K7QM2XRD4P
https://renoop.duckdns.org/i/K7QM2-XRD4P/ => K7QM2XRD4P
renoop://friends/add/?c=K7QM2-XRD4P => K7QM2XRD4P
K7QM2\tXRD4P => K7QM2XRD4P
Add me on reNOOP Friends => nil
our friendship means everything => nil
\u{418}\u{43B}\u{438} \u{432}\u{432}\u{435}\u{434}\u{438} \u{43A}\u{43E}\u{434} \u{43D}\u{430} \u{432}\u{43A}\u{43B}\u{430}\u{434}\u{43A}\u{435} \u{AB}\u{414}\u{440}\u{443}\u{437}\u{44C}\u{44F}\u{BB}. => nil
call me - maybe => nil
see https://example.org/other/K7QM2XRD4P today => nil
friendship => FR1ENDSH1P
 => nil
\u{20}\u{20} => nil
\r\n => nil
\u{20} k7qm2-xrd4p\n => K7QM2XRD4P
K7QM2-XRD4P. => K7QM2XRD4P
K7QM2-XRD4P! => K7QM2XRD4P
K7QM2-XRD4P? => K7QM2XRD4P
K7QM2-XRD4P, => K7QM2XRD4P
K7QM2-XRD4P; => K7QM2XRD4P
K7QM2-XRD4P: => K7QM2XRD4P
K7QM2-XRD4P) => K7QM2XRD4P
K7QM2-XRD4P... => K7QM2XRD4P
K7QM2-XRD4P.,;:!?) => K7QM2XRD4P
(K7QM2-XRD4P) => nil
\u{AB}K7QM2-XRD4P\u{BB} => nil
"K7QM2-XRD4P" => nil
'K7QM2-XRD4P' => nil
code: K7QM2-XRD4P => K7QM2XRD4P
code:K7QM2-XRD4P => nil
code:\tK7QM2-XRD4P => K7QM2XRD4P
CODE K7QM2XRD4P => nil
code K7QM2-XRD4 => nil
code K7QM2-XRD4PP => nil
code K7QM2XRD4P- => nil
code K7QM-2XRD4P => nil
code k7qm2-xrd4p! => K7QM2XRD4P
code O12L4-I6789 => 0121416789
AAAAA-BBBBB CCCCC-DDDDD => AAAAABBBBB
AAAAABBBBB CCCCC-DDDDD => CCCCCDDDDD
K7QM2 XRD4P => K7QM2XRD4P
my K7QM2 XRD4P => nil
K7QM2-XRD4P K7QM2-XRD4P => K7QM2XRD4P
K7QM2-\nXRD4P => K7QM2XRD4P
K7QM2\n-XRD4P => K7QM2XRD4P
k7qm2-xrd4p\r\n\r\n => K7QM2XRD4P
https://renoop.duckdns.org/i/K7QM2-XRD4P?utm=1 => K7QM2XRD4P
https://renoop.duckdns.org/i/K7QM2-XRD4P#top => K7QM2XRD4P
https://renoop.duckdns.org/i/K7QM2-XRD4P/extra => nil
https://x/i/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/I/K7QM2-XRD4P => nil
https://x//i//K7QM2-XRD4P// => K7QM2XRD4P
x/i/K7QM2-XRD4P => K7QM2XRD4P
/i/K7QM2-XRD4P => K7QM2XRD4P
i/K7QM2-XRD4P => K7QM2XRD4P
//i/K7QM2-XRD4P => nil
///i/K7QM2-XRD4P => K7QM2XRD4P
./i/K7QM2-XRD4P => K7QM2XRD4P
../i/K7QM2-XRD4P => K7QM2XRD4P
https://renoop.duckdns.org/i/K7QM2%2DXRD4P => K7QM2XRD4P
https://x/i/K7QM2-XRD4P%20 => K7QM2XRD4P
https://x/i/%20K7QM2-XRD4P => K7QM2XRD4P
https://x/%69/K7QM2-XRD4P => K7QM2XRD4P
https://x/i%2FK7QM2-XRD4P => K7QM2XRD4P
https://x/i/K7QM2-XRD4%50 => K7QM2XRD4P
https://x/i/K7QM2-XRD4%70 => K7QM2XRD4P
https://x/%2Fi/K7QM2-XRD4P => K7QM2XRD4P
https://x/i/K7QM2-XRD4P% => nil
https://x/i/%zzK7QM2-XRD4P => nil
https://x/i/K7QM2-XRD4P%2 => nil
https://x/i/K7QM2%C3%9FXRD4 => nil
https://x/i/K7QM2-XRD4P%FF => nil
https://x/i/K7QM2-XRD4P%00 => nil
HTTPS://X/i/k7qm2-xrd4p => K7QM2XRD4P
ftp://x/i/K7QM2-XRD4P => K7QM2XRD4P
mailto:a/i/K7QM2-XRD4P => K7QM2XRD4P
x:i/K7QM2-XRD4P => K7QM2XRD4P
x:/i/K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/i/K7QM2-XRD4P => nil
renoop:/i/K7QM2-XRD4P => nil
renoop:i/K7QM2-XRD4P => nil
RENOOP://FRIENDS/add?c=K7QM2-XRD4P => K7QM2XRD4P
ReNoop://Friends/add?c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?c=K7QM2-XRD4P&c=AAAAA-BBBBB => K7QM2XRD4P
renoop://friends/add?x=1&c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?c=bad&c=K7QM2-XRD4P => nil
renoop://friends/add?c => nil
renoop://friends/add?c= => nil
renoop://friends/add?c&c=K7QM2-XRD4P => nil
renoop://friends/add?c=K7QM2%2DXRD4P => K7QM2XRD4P
renoop://friends/add?c=K7QM2+XRD4P => nil
renoop://friends/add?c=K7QM2%20XRD4P => K7QM2XRD4P
renoop://friends/add?%63=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?C=K7QM2-XRD4P => nil
renoop://friends/add#c=K7QM2-XRD4P => nil
renoop://friends/add?c=K7QM2-XRD4P#x => K7QM2XRD4P
renoop://friends/Add?c=K7QM2-XRD4P => nil
renoop://friends/add//?c=K7QM2-XRD4P => nil
renoop://friends/%61dd?c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends:80/add?c=K7QM2-XRD4P => K7QM2XRD4P
renoop://u@friends/add?c=K7QM2-XRD4P => K7QM2XRD4P
renoop://u:p@friends:1/add?c=K7QM2-XRD4P => K7QM2XRD4P
renoop:friends/add?c=K7QM2-XRD4P => nil
renoop:///add?c=K7QM2-XRD4P => nil
renoop://friends.example/add?c=K7QM2-XRD4P => nil
renoop://friends?c=K7QM2-XRD4P => nil
renoop://friends/?c=K7QM2-XRD4P => nil
renoop://friends/add?c=K7QM2-XRD4P. => K7QM2XRD4P
renoop://friends/add?c=K7QM2-XRD4P;x=1 => nil
renoop://friends/add?a=b=c&c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?&&c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?c=K7QM2-XRD4P= => nil
renoop://friends/add?c==K7QM2-XRD4P => nil
renoop://fri%65nds/add?c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?c=K7QM2-XRD4P or the code => K7QM2XRD4P
open renoop://friends/add?c=K7QM2-XRD4P, please => K7QM2XRD4P
code\u{A0}K7QM2-XRD4P => K7QM2XRD4P
code\u{2028}K7QM2-XRD4P => K7QM2XRD4P
code\u{85}K7QM2-XRD4P => K7QM2XRD4P
code\u{1C}K7QM2-XRD4P => nil
code\u{200B}K7QM2-XRD4P => K7QM2XRD4P
code\u{3000}K7QM2-XRD4P => K7QM2XRD4P
code\u{B}K7QM2-XRD4P => K7QM2XRD4P
code\u{C}K7QM2-XRD4P => K7QM2XRD4P
code\u{FEFF}K7QM2-XRD4P => nil
code\u{180E}K7QM2-XRD4P => nil
\u{A0}K7QM2-XRD4P\u{A0} => K7QM2XRD4P
\u{85}K7QM2-XRD4P\u{85} => K7QM2XRD4P
\u{1C}K7QM2-XRD4P => nil
\u{200B}K7QM2-XRD4P => K7QM2XRD4P
\u{FEFF}https://x/i/K7QM2-XRD4P => nil
https://renoop.duckdns.org/i/K7QM2-XRD4P, => K7QM2XRD4P
https://renoop.duckdns.org/i/K7QM2-XRD4P; => K7QM2XRD4P
https://renoop.duckdns.org/i/K7QM2-XRD4P: => K7QM2XRD4P
https://renoop.duckdns.org/i/K7QM2-XRD4P? => K7QM2XRD4P
https://renoop.duckdns.org/i/K7QM2-XRD4P! => K7QM2XRD4P
https://renoop.duckdns.org/i/K7QM2-XRD4P) => K7QM2XRD4P
(https://renoop.duckdns.org/i/K7QM2-XRD4P) => nil
<https://renoop.duckdns.org/i/K7QM2-XRD4P> => nil
[https://renoop.duckdns.org/i/K7QM2-XRD4P] => nil
https://\u{43F}\u{440}\u{438}\u{43C}\u{435}\u{440}.\u{440}\u{444}/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/\u{43F}\u{443}\u{442}\u{44C}/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/i/K7QM2-XRD4P< => nil
ht!tp://x/i/K7QM2-XRD4P => nil
1http://x/i/K7QM2-XRD4P => nil
://x/i/K7QM2-XRD4P => K7QM2XRD4P
:/i/K7QM2-XRD4P => K7QM2XRD4P
a:b:c/i/K7QM2-XRD4P => K7QM2XRD4P
12:30/i/K7QM2-XRD4P => nil
[x]/i/K7QM2-XRD4P => K7QM2XRD4P
http://[::1]/i/K7QM2-XRD4P => K7QM2XRD4P
http://[bad/i/K7QM2-XRD4P => nil
http://x:y/i/K7QM2-XRD4P => nil
http://x:99999999999/i/K7QM2-XRD4P => K7QM2XRD4P
http://x:8787/i/K7QM2-XRD4P => K7QM2XRD4P
http://127.0.0.1:8787/i/K7QM2-XRD4P => K7QM2XRD4P
x?y/i/K7QM2-XRD4P => nil
x#/i/K7QM2-XRD4P => nil
http://x/i/K7QM2-XRD4P?c=/i/AAAAA-BBBBB => K7QM2XRD4P
http://x/a?b/i/K7QM2-XRD4P => nil
\\i\\K7QM2-XRD4P => nil
http://x/i/K7QM2-XRD4P\\ => nil
http://x\\i\\K7QM2-XRD4P => nil
{x}/i/K7QM2-XRD4P => K7QM2XRD4P
x|y/i/K7QM2-XRD4P => K7QM2XRD4P
x^/i/K7QM2-XRD4P => K7QM2XRD4P
`/i/K7QM2-XRD4P => K7QM2XRD4P
"/i/K7QM2-XRD4P => K7QM2XRD4P
http://x/i/K7QM2-XRD4P" => nil
http://x/i/"K7QM2-XRD4P => nil
http://x /i/K7QM2-XRD4P => K7QM2XRD4P
http://x/i/ K7QM2-XRD4P => K7QM2XRD4P
http://x/i/K7QM2 XRD4P => K7QM2XRD4P
http://x/i/K7QM2-XRD4P  now => K7QM2XRD4P
http://x/i/K7QM2XRD4P => K7QM2XRD4P
http://x/i/k7qm2xrd4p => K7QM2XRD4P
http://x/i/FRIENDSHIP => FR1ENDSH1P
http://x/i/K7QM2-XRD4 => nil
http://x/i/ => nil
http://x/i => nil
http://i/K7QM2-XRD4P => nil
http://x/i/K7QM2-XRD4P/. => K7QM2XRD4P
http://x/i/K7QM2-XRD4P/.. => K7QM2XRD4P
http://x/i/./K7QM2-XRD4P => nil
http://x/i/K7QM2-XRD4P;v=1 => nil
http://x/i;v=1/K7QM2-XRD4P => nil
http://x/a/b/c/d/i/K7QM2-XRD4P => K7QM2XRD4P
http://x/i/K7QM2-XRD4P http://y/i/AAAAA-BBBBB => AAAAABBBBB
junk/i/nope http://y/i/AAAAA-BBBBB => AAAAABBBBB
http://x/i/nope AAAAA-BBBBB => AAAAABBBBB
renoop://friends/add?c=nope AAAAA-BBBBB => AAAAABBBBB
Mein Code: K7QM2-XRD4P. => K7QM2XRD4P
Mon code\u{A0}: K7QM2-XRD4P => K7QM2XRD4P
\u{6211}\u{7684}\u{9080}\u{8BF7}\u{7801}\u{FF1A}K7QM2-XRD4P => nil
\u{6211}\u{7684}\u{9080}\u{8BF7}\u{7801} K7QM2-XRD4P\u{3002} => nil
\u{6211}\u{7684}\u{9080}\u{8BF7}\u{7801}\u{FF1A}https://renoop.duckdns.org/i/K7QM2-XRD4P => nil
https://renoop.duckdns.org/i/K7QM2-XRD4P\u{3002} => nil
https://renoop.duckdns.org/i/K7QM2-XRD4P\u{FF0C} => nil
K7QM2-XRD4P\u{2026} => nil
code\u{1680}K7QM2-XRD4P => K7QM2XRD4P
code\u{2000}K7QM2-XRD4P => K7QM2XRD4P
code\u{2001}K7QM2-XRD4P => K7QM2XRD4P
code\u{2002}K7QM2-XRD4P => K7QM2XRD4P
code\u{2003}K7QM2-XRD4P => K7QM2XRD4P
code\u{2004}K7QM2-XRD4P => K7QM2XRD4P
code\u{2005}K7QM2-XRD4P => K7QM2XRD4P
code\u{2006}K7QM2-XRD4P => K7QM2XRD4P
code\u{2007}K7QM2-XRD4P => K7QM2XRD4P
code\u{2008}K7QM2-XRD4P => K7QM2XRD4P
code\u{2009}K7QM2-XRD4P => K7QM2XRD4P
code\u{200A}K7QM2-XRD4P => K7QM2XRD4P
code\u{200B}K7QM2-XRD4P => K7QM2XRD4P
code\u{200C}K7QM2-XRD4P => nil
code\u{200D}K7QM2-XRD4P => nil
code\u{200E}K7QM2-XRD4P => nil
code\u{2029}K7QM2-XRD4P => K7QM2XRD4P
code\u{202F}K7QM2-XRD4P => K7QM2XRD4P
code\u{205F}K7QM2-XRD4P => K7QM2XRD4P
code\u{2060}K7QM2-XRD4P => nil
code\u{0}K7QM2-XRD4P => nil
code\u{1}K7QM2-XRD4P => nil
code\u{8}K7QM2-XRD4P => nil
code\u{E}K7QM2-XRD4P => nil
code\u{1D}K7QM2-XRD4P => nil
code\u{1E}K7QM2-XRD4P => nil
code\u{1F}K7QM2-XRD4P => nil
code\u{7F}K7QM2-XRD4P => nil
code\u{84}K7QM2-XRD4P => nil
code\u{86}K7QM2-XRD4P => nil
code\u{AD}K7QM2-XRD4P => nil
code\u{2027}K7QM2-XRD4P => nil
code\u{202A}K7QM2-XRD4P => nil
code\u{2800}K7QM2-XRD4P => nil
code\u{3164}K7QM2-XRD4P => nil
code\u{FFA0}K7QM2-XRD4P => nil
code\u{E0020}K7QM2-XRD4P => nil
https://x/%zz/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/%/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/a%2/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/i/K7QM2-XRD4P?a=%zz => K7QM2XRD4P
https://x/i/K7QM2-XRD4P?% => K7QM2XRD4P
https://x/i/K7QM2-XRD4P#%zz => K7QM2XRD4P
https://x/i/K7QM2-XRD4P#% => K7QM2XRD4P
https://%zz/i/K7QM2-XRD4P => nil
https://x%/i/K7QM2-XRD4P => nil
https://x%41/i/K7QM2-XRD4P => K7QM2XRD4P
https://u%zz@x/i/K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?c=K7QM2-XRD4P&x=%zz => K7QM2XRD4P
renoop://friends/add?x=%zz&c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?c=K7QM2-XRD4P#%zz => K7QM2XRD4P
renoop://friends/add?c=%4B7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?c=K7QM2-XRD4P% => nil
renoop://friends/add?%63%3D=K7QM2-XRD4P => nil
renoop://friends/add?c%3DK7QM2-XRD4P => nil
renoop://friends/add?c=K7QM2%26c=XRD4P => nil
renoop://friends/add?c=K7QM2-XRD4P%26x => nil
https://x/%FF/i/K7QM2-XRD4P => nil
https://x/%C3/i/K7QM2-XRD4P => nil
https://x/%C3%9F/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/%00/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/%E2%82/i/K7QM2-XRD4P => nil
https://x/i/K7QM2-XRD4P?a=%FF => K7QM2XRD4P
renoop://friends/add?x=%FF&c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?c=K7QM2-XRD4P&x=%FF => K7QM2XRD4P
renoop://friends/add?c=%FFK7QM2-XRD4P => nil
renoop://friends/%FF/add?c=K7QM2-XRD4P => nil
renoop://fri%FFends/add?c=K7QM2-XRD4P => nil
https://x/i/K7QM2-XRD4P%3Fx => nil
https://x/i/K7QM2-XRD4P%23x => nil
https://x/i%3F/K7QM2-XRD4P => nil
https://x/i/K7QM2-\nXRD4P => K7QM2XRD4P
https://x/i/K7QM2\tXRD4P => K7QM2XRD4P
https://x/i/K7QM2-XRD4P\nnext line => K7QM2XRD4P
https://x/a\tb/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/a b/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/<a>/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/a\u{0}b/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/a\u{1}b/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/a\u{7F}/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/a\u{85}b/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/a\u{1F600}/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/a"b/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/a\\b/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/a[b]/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/a{b}/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/a%b/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/i/K7QM2-XRD4P#a#b => K7QM2XRD4P
https://x/i/K7QM2-XRD4P?a?b => K7QM2XRD4P
https://x/i/K7QM2-XRD4P?a#b?c => K7QM2XRD4P
https://x/i/K7QM2-XRD4P#a?b => K7QM2XRD4P
https://x:/i/K7QM2-XRD4P => K7QM2XRD4P
https://x:8a/i/K7QM2-XRD4P => nil
https://x:-1/i/K7QM2-XRD4P => nil
https://x:+1/i/K7QM2-XRD4P => nil
https://x:\u{661}/i/K7QM2-XRD4P => nil
https://a@b@c/i/K7QM2-XRD4P => K7QM2XRD4P
https://a:b:c/i/K7QM2-XRD4P => nil
https://a:1:2/i/K7QM2-XRD4P => nil
https://@x/i/K7QM2-XRD4P => K7QM2XRD4P
https://:@x/i/K7QM2-XRD4P => K7QM2XRD4P
https://u:p@/i/K7QM2-XRD4P => K7QM2XRD4P
https://[zz]/i/K7QM2-XRD4P => K7QM2XRD4P
https://[::1/i/K7QM2-XRD4P => nil
https://[::1]x/i/K7QM2-XRD4P => nil
https://[::1]:80/i/K7QM2-XRD4P => K7QM2XRD4P
https://[::1]:x/i/K7QM2-XRD4P => nil
https://[]/i/K7QM2-XRD4P => K7QM2XRD4P
https://x]/i/K7QM2-XRD4P => nil
https://x[/i/K7QM2-XRD4P => nil
https://x y/i/K7QM2-XRD4P => K7QM2XRD4P
https://x<y/i/K7QM2-XRD4P => nil
https://x"y/i/K7QM2-XRD4P => nil
https://x\\y/i/K7QM2-XRD4P => nil
https://x{y}/i/K7QM2-XRD4P => nil
https://x|y/i/K7QM2-XRD4P => nil
https://x^y/i/K7QM2-XRD4P => nil
https://x`y/i/K7QM2-XRD4P => nil
https://x%20y/i/K7QM2-XRD4P => K7QM2XRD4P
https://x_y/i/K7QM2-XRD4P => K7QM2XRD4P
https://x..y/i/K7QM2-XRD4P => K7QM2XRD4P
https://-x/i/K7QM2-XRD4P => K7QM2XRD4P
https://x\ty/i/K7QM2-XRD4P => K7QM2XRD4P
https:///i/K7QM2-XRD4P => K7QM2XRD4P
https:////i/K7QM2-XRD4P => K7QM2XRD4P
https:/i/K7QM2-XRD4P => K7QM2XRD4P
https:i/K7QM2-XRD4P => K7QM2XRD4P
https: => nil
https:// => nil
: => nil
:: => nil
a: => nil
a:/ => nil
a:// => nil
+a:/i/K7QM2-XRD4P => nil
a+b.c-d:/i/K7QM2-XRD4P => K7QM2XRD4P
a_b:/i/K7QM2-XRD4P => nil
a%41:/i/K7QM2-XRD4P => nil
\u{E9}:/i/K7QM2-XRD4P => nil
a\u{E9}:/i/K7QM2-XRD4P => nil
a b:/i/K7QM2-XRD4P => K7QM2XRD4P
a/b:c/i/K7QM2-XRD4P => K7QM2XRD4P
a?b:c/i/K7QM2-XRD4P => nil
a#b:c/i/K7QM2-XRD4P => nil
/a:b/i/K7QM2-XRD4P => K7QM2XRD4P
.:/i/K7QM2-XRD4P => nil
a.:/i/K7QM2-XRD4P => K7QM2XRD4P
A:/i/K7QM2-XRD4P => K7QM2XRD4P
z9+.-:/i/K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?c=K7QM2-XRD4P& => K7QM2XRD4P
renoop://friends/add?=K7QM2-XRD4P&c=AAAAA-BBBBB => AAAAABBBBB
renoop://friends/add?c=K7QM2-XRD4P?c=AAAAA-BBBBB => nil
renoop://friends/add??c=K7QM2-XRD4P => nil
renoop://friends/add?c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?\u{441}=K7QM2-XRD4P => nil
renoop://friends/add?c=K7QM2-XRD4P\u{A0} => K7QM2XRD4P
renoop://friends/add?c=\u{FF2B}7QM2-XRD4P => nil
renoop://FRIEND\u{17F}/add?c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends./add?c=K7QM2-XRD4P => nil
renoop://friends/add/add?c=K7QM2-XRD4P => nil
renoop://friends//add?c=K7QM2-XRD4P => nil
renoop://friends/add%2F?c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add%20?c=K7QM2-XRD4P => nil
renoop://friends/add ?c=K7QM2-XRD4P => nil
renoop://friends /add?c=K7QM2-XRD4P => nil
renoop://friends\u{A0}/add?c=K7QM2-XRD4P => nil
renoop://[friends]/add?c=K7QM2-XRD4P => nil
renoop://friends@x/add?c=K7QM2-XRD4P => nil
renoop://x@friends@y/add?c=K7QM2-XRD4P => nil
renoop://friends:x/add?c=K7QM2-XRD4P => nil
renoop://friends:/add?c=K7QM2-XRD4P => K7QM2XRD4P
renoop://@friends/add?c=K7QM2-XRD4P => K7QM2XRD4P
renoop://:80/add?c=K7QM2-XRD4P => nil
\u{130}/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/\u{130}/K7QM2-XRD4P => nil
https://x/i\u{307}/K7QM2-XRD4P => nil
https://x/i/K7QM2-XRD4P\u{200B} => K7QM2XRD4P
https://x/i/K7QM2-XRD4P\u{A0}more => K7QM2XRD4P
https://renoop.duckdns.org/i/K7QM2-XRD4P\u{A0} => K7QM2XRD4P
K7QM2-XRD4P\u{A0}! => K7QM2XRD4P
K7QM2-XRD4P \u{2014} my code => K7QM2XRD4P
K7QM2-XRD4P\u{2014} => nil
code=K7QM2-XRD4P => nil
code:\u{A0}K7QM2-XRD4P => K7QM2XRD4P
K7QM2-XRD4P: => K7QM2XRD4P
K7QM2-XRD4P:x => nil
.K7QM2-XRD4P => nil
K7QM2-XRD4P)). => K7QM2XRD4P
K7QM2-XRD4P => K7QM2XRD4P
K7QM2\u{2010}XRD4P => nil
K7QM2\u{2011}XRD4P => nil
K7QM2\u{2212}XRD4P => nil
K7QM2\u{FF0D}XRD4P => nil
k7qm2-xr\u{DF}P => K7QM2XRSSP
k7qm2-xrd\u{FB01} => K7QM2XRDF1
code k7qm2-xr\u{DF}P => nil
code k7qm2-xrd\u{FB01} => nil
code K7QM\u{DF}-XRD4P => nil
code K7QM2-XRD\u{DF} => nil
code K7Q\u{FB01}-XRD4P => nil
https://x!y/i/K7QM2-XRD4P => K7QM2XRD4P
https://x\u{24}y/i/K7QM2-XRD4P => K7QM2XRD4P
https://x&y/i/K7QM2-XRD4P => K7QM2XRD4P
https://x'y/i/K7QM2-XRD4P => K7QM2XRD4P
https://x(y)/i/K7QM2-XRD4P => K7QM2XRD4P
https://x*y/i/K7QM2-XRD4P => K7QM2XRD4P
https://x+y/i/K7QM2-XRD4P => K7QM2XRD4P
https://x,y/i/K7QM2-XRD4P => K7QM2XRD4P
https://x;y/i/K7QM2-XRD4P => K7QM2XRD4P
https://x=y/i/K7QM2-XRD4P => K7QM2XRD4P
https://x~y/i/K7QM2-XRD4P => K7QM2XRD4P
https://x>y/i/K7QM2-XRD4P => nil
https://x\u{1}y/i/K7QM2-XRD4P => nil
https://x\u{7F}y/i/K7QM2-XRD4P => nil
https://x\u{0}y/i/K7QM2-XRD4P => nil
https://a b/i/AAAAA-BBBBB https://c/i/CCCCC-DDDDD => AAAAABBBBB
https://a/i/AAAAA-BBBBB https://c/i/CCCCC-DDDDD => CCCCCDDDDD
https://a/i/AAAAA-BBBBB ok? https://c/i/CCCCC-DDDDD => AAAAABBBBB
https://a/i/AAAAA-BBBBB #1 https://c/i/CCCCC-DDDDD => AAAAABBBBB
https://a/i/AAAAA-BBBBB\nhttps://c/i/CCCCC-DDDDD => CCCCCDDDDD
https://a b@c/i/AAAAA-BBBBB https://c/i/CCCCC-DDDDD => CCCCCDDDDD
https://x/a b/%69/K7QM2-XRD4P => K7QM2XRD4P
https://x/a b/%zz/%69/K7QM2-XRD4P => nil
https://x/<>/%69/K7QM2%2DXRD4P => nil
https://u<v@x/i/K7QM2-XRD4P => K7QM2XRD4P
https://u[v@x/i/K7QM2-XRD4P => K7QM2XRD4P
https://u]v@x/i/K7QM2-XRD4P => K7QM2XRD4P
https://u:p:q@x/i/K7QM2-XRD4P => K7QM2XRD4P
https://u"v@x/i/K7QM2-XRD4P => K7QM2XRD4P
https://u\\v@x/i/K7QM2-XRD4P => K7QM2XRD4P
https://u%FFv@x/i/K7QM2-XRD4P => K7QM2XRD4P
https://u/v@x/i/K7QM2-XRD4P => K7QM2XRD4P
https://ex\u{E4}mple.org/i/K7QM2-XRD4P => K7QM2XRD4P
https://\u{1F600}.example/i/K7QM2-XRD4P => K7QM2XRD4P
https://\u{3002}/i/K7QM2-XRD4P => nil
https://\u{FF58}/i/K7QM2-XRD4P => K7QM2XRD4P
https://a\u{301}/i/K7QM2-XRD4P => K7QM2XRD4P
https://\u{378}/i/K7QM2-XRD4P => nil
https://\u{FFFD}/i/K7QM2-XRD4P => nil
https://xn--zz/i/K7QM2-XRD4P => K7QM2XRD4P
https://xn--/i/K7QM2-XRD4P => K7QM2XRD4P
https://XN--D1ACUFC.XN--P1AI/i/K7QM2-XRD4P => K7QM2XRD4P
https://a\u{AD}b/i/K7QM2-XRD4P => K7QM2XRD4P
https://\u{AD}/i/K7QM2-XRD4P => nil
https://a\u{2060}b/i/K7QM2-XRD4P => K7QM2XRD4P
https://a\u{FEFF}b/i/K7QM2-XRD4P => K7QM2XRD4P
https://a\u{200D}b/i/K7QM2-XRD4P => nil
https://\u{E9}/i/K7QM2-XRD4P => K7QM2XRD4P
https://\u{DF}/i/K7QM2-XRD4P => K7QM2XRD4P
https://\u{4E2D}\u{6587}.\u{4E2D}\u{56FD}/i/K7QM2-XRD4P => K7QM2XRD4P
https://a.\u{3002}b/i/K7QM2-XRD4P => nil
https://a%C3%A9/i/K7QM2-XRD4P => K7QM2XRD4P
https://a%2Fb/i/K7QM2-XRD4P => K7QM2XRD4P
https://a%3Ab/i/K7QM2-XRD4P => K7QM2XRD4P
https://a%40b/i/K7QM2-XRD4P => K7QM2XRD4P
https://a%5Bb/i/K7QM2-XRD4P => K7QM2XRD4P
https://a%00b/i/K7QM2-XRD4P => K7QM2XRD4P
https://a%20b/i/K7QM2-XRD4P => K7QM2XRD4P
https://a%7Fb/i/K7QM2-XRD4P => K7QM2XRD4P
https://%41/i/K7QM2-XRD4P => K7QM2XRD4P
renoop://\u{FF46}riends/add?c=K7QM2-XRD4P => K7QM2XRD4P
renoop://fri\u{AD}ends/add?c=K7QM2-XRD4P => K7QM2XRD4P
renoop://FRIENDS\u{3002}/add?c=K7QM2-XRD4P => nil
renoop://friend\u{DF}/add?c=K7QM2-XRD4P => nil
renoop://frie\u{301}nds/add?c=K7QM2-XRD4P => nil
renoop://fr\u{131}ends/add?c=K7QM2-XRD4P => nil
renoop://FR\u{130}ENDS/add?c=K7QM2-XRD4P => nil
renoop://\u{24D5}riends/add?c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends%2E/add?c=K7QM2-XRD4P => nil
renoop://%46RIENDS/add?c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends%00/add?c=K7QM2-XRD4P => nil
renoop://friends%20/add?c=K7QM2-XRD4P => nil
//friends/add?c=K7QM2-XRD4P => nil
//x/i/K7QM2-XRD4P => K7QM2XRD4P
//x/a/i/K7QM2-XRD4P => K7QM2XRD4P
//x:y/a/i/K7QM2-XRD4P => nil
//x<y/a/i/K7QM2-XRD4P => nil
https://x/i/K7QM2-XRD4P%2F => K7QM2XRD4P
https://x/i%2F%2FK7QM2-XRD4P => K7QM2XRD4P
https://x/i/%2F/K7QM2-XRD4P => K7QM2XRD4P
https://x/i/K7QM2%2dXRD4P => K7QM2XRD4P
https://x/i/k7qm2%2dxrd4p => K7QM2XRD4P
renoop://friends/add?%FF=1&c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?%zz=1&c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?c%=K7QM2-XRD4P => nil
renoop://friends/add?c%FF=1&c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?c=%FF&c=K7QM2-XRD4P => nil
renoop://friends/add?c=%zz&c=K7QM2-XRD4P => nil
renoop://friends/add?c=K7QM2%2dXRD4P => K7QM2XRD4P
renoop://friends/add?#c=K7QM2-XRD4P => nil
renoop://friends/add?c=K7QM2 XRD4P => K7QM2XRD4P
renoop://friends/add?c=K7QM2-\nXRD4P => K7QM2XRD4P
renoop://friends/add?c=K7QM2-XRD4P\n => K7QM2XRD4P
renoop://friends/add?c= K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add? c=K7QM2-XRD4P => nil
renoop://friends/add?c =K7QM2-XRD4P => nil
renoop://friends/add?x=a b&c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?x=<>&c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?x="&c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?x=\u{E9}&c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?c=K7QM2-XRD4P&x=\u{1F600} => K7QM2XRD4P
renoop://friends/add?x[]=1&c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?x=%&c=K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?c=%4b7qm2-xrd4p => K7QM2XRD4P
renoop://friends/add?c=K7QM2-XRD4P%0A => K7QM2XRD4P
renoop://friends/add?c=%E2%80%8BK7QM2-XRD4P => nil
renoop://friends/add?c=K7QM2%09XRD4P => K7QM2XRD4P
renoop://friends/add?c=K7QM2%C2%A0XRD4P => nil
renoop:?c=K7QM2-XRD4P => nil
renoop://friends?add&c=K7QM2-XRD4P => nil
[::1]/i/K7QM2-XRD4P => K7QM2XRD4P
[x:y]/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/i/K7QM2-XRD4P%ED%A0%80 => nil
https://x/%ED%A0%80/i/K7QM2-XRD4P => nil
https://x/%C0%AF/i/K7QM2-XRD4P => nil
https://x/%F4%90%80%80/i/K7QM2-XRD4P => nil
https://x/%F0%9F%98%80/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/%EF%BB%BF/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/%EF%BF%BE/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/%80/i/K7QM2-XRD4P => nil
https://x/%e9/i/K7QM2-XRD4P => nil
https://x/%C3%A9%zz/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/%zz%FF/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/%%69/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/%2569/K7QM2-XRD4P => nil
https://x/%25zz/i/K7QM2-XRD4P => K7QM2XRD4P
https://x/i/K7QM2-XRD4P?%FF => K7QM2XRD4P
https://x/i/K7QM2-XRD4P#%FF => K7QM2XRD4P
https://x:80:/i/K7QM2-XRD4P => nil
https://x::80/i/K7QM2-XRD4P => nil
https://x: 80/i/K7QM2-XRD4P => K7QM2XRD4P
https://x:80 /i/K7QM2-XRD4P => K7QM2XRD4P
https://[::1]:/i/K7QM2-XRD4P => K7QM2XRD4P
https://[a]b]/i/K7QM2-XRD4P => nil
https://[a[b]/i/K7QM2-XRD4P => nil
https://[a b]/i/K7QM2-XRD4P => K7QM2XRD4P
https://[%zz]/i/K7QM2-XRD4P => K7QM2XRD4P
https://[<]/i/K7QM2-XRD4P => nil
https://[\u{E9}]/i/K7QM2-XRD4P => nil
https://u@[::1]/i/K7QM2-XRD4P => K7QM2XRD4P
https://[::1]@x/i/K7QM2-XRD4P => K7QM2XRD4P
https://x@[/i/K7QM2-XRD4P => nil
http:x/i/K7QM2-XRD4P? => K7QM2XRD4P
http:?/i/K7QM2-XRD4P => nil
http:#/i/K7QM2-XRD4P => nil
a://b/i => nil
a://i/K7QM2-XRD4P/ => nil
ht\ttp://x/i/K7QM2-XRD4P => K7QM2XRD4P
http\u{A0}://x/i/K7QM2-XRD4P => K7QM2XRD4P
http:\u{A0}//x/i/K7QM2-XRD4P => K7QM2XRD4P
K7QM2-XRD4P \u{A0} => K7QM2XRD4P
\u{200B}\u{200B}K7QM2-XRD4P\u{200B} => K7QM2XRD4P
K7QM2\u{200B}XRD4P => nil
K7QM2-XRD4P\u{200B}. => K7QM2XRD4P
K7QM2-XRD4P.\u{200B} => K7QM2XRD4P
https://renoop.duckdns.org/i/K7QM2-XRD4P. \u{20} => K7QM2XRD4P
https://x/%69/K7QM2-XRD4P?a=< => K7QM2XRD4P
https://x/%69/K7QM2-XRD4P#< => K7QM2XRD4P
https://u<v@x/%69/K7QM2-XRD4P => K7QM2XRD4P
https://x/%69/K7QM2-XRD4P?a=%zz => K7QM2XRD4P
https://x/%69/K7QM2-XRD4P#%zz => K7QM2XRD4P
https://u%zz@x/%69/K7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?c=%4B7QM2-XRD4P#< => K7QM2XRD4P
renoop://friends/add?c=%4B7QM2-XRD4P#%zz => K7QM2XRD4P
renoop://u<v@friends/add?c=%4B7QM2-XRD4P => K7QM2XRD4P
renoop://u%zz@friends/add?c=%4B7QM2-XRD4P => K7QM2XRD4P
renoop://u<v@fri%65nds/%61dd?c=%4B7QM2-XRD4P#< => K7QM2XRD4P
renoop://friends/%61dd?c=K7QM2-XRD4P&x=< => K7QM2XRD4P
renoop://friends/%61dd?c=K7QM2-XRD4P&x=%zz => K7QM2XRD4P
renoop://fri%65nds/add?c=K7QM2-XRD4P&x=< => K7QM2XRD4P
https://x/a!b/%69/K7QM2-XRD4P => K7QM2XRD4P
https://x/a\u{24}b/%69/K7QM2-XRD4P => K7QM2XRD4P
https://x/a&b/%69/K7QM2-XRD4P => K7QM2XRD4P
https://x/a'b/%69/K7QM2-XRD4P => K7QM2XRD4P
https://x/a(b/%69/K7QM2-XRD4P => K7QM2XRD4P
https://x/a)b/%69/K7QM2-XRD4P => K7QM2XRD4P
https://x/a*b/%69/K7QM2-XRD4P => K7QM2XRD4P
https://x/a+b/%69/K7QM2-XRD4P => K7QM2XRD4P
https://x/a,b/%69/K7QM2-XRD4P => K7QM2XRD4P
https://x/a;b/%69/K7QM2-XRD4P => K7QM2XRD4P
https://x/a=b/%69/K7QM2-XRD4P => K7QM2XRD4P
https://x/a:b/%69/K7QM2-XRD4P => K7QM2XRD4P
https://x/a@b/%69/K7QM2-XRD4P => K7QM2XRD4P
https://x/a~b/%69/K7QM2-XRD4P => K7QM2XRD4P
https://x/a"b/%69/K7QM2-XRD4P => nil
https://x/a<b/%69/K7QM2-XRD4P => nil
https://x/a>b/%69/K7QM2-XRD4P => nil
https://x/a\\b/%69/K7QM2-XRD4P => nil
https://x/a^b/%69/K7QM2-XRD4P => nil
https://x/a`b/%69/K7QM2-XRD4P => nil
https://x/a{b/%69/K7QM2-XRD4P => nil
https://x/a|b/%69/K7QM2-XRD4P => nil
https://x/a}b/%69/K7QM2-XRD4P => nil
https://x/a[b/%69/K7QM2-XRD4P => nil
https://x/a]b/%69/K7QM2-XRD4P => nil
https://x/\u{E9}/%69/K7QM2-XRD4P => nil
https://x/a\u{1}b/%69/K7QM2-XRD4P => nil
https://x/a\u{7F}b/%69/K7QM2-XRD4P => nil
https://x/a\u{0}b/%69/K7QM2-XRD4P => nil
https://a/i/AAAAA-BBBBB https://c/%69/CCCCC-DDDDD => AAAAABBBBB
https://a/i/AAAAA-BBBBB\thttps://c/%69/CCCCC-DDDDD => AAAAABBBBB
https://a/i/AAAAA-BBBBB\nhttps://c/%69/CCCCC-DDDDD => AAAAABBBBB
https://a/i/AAAAA-BBBBB https://c/i/CCCCC%2DDDDDD => AAAAABBBBB
renoop://friends/add?x=!&c=%4B7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?x=\u{24}&c=%4B7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?x='&c=%4B7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?x=(&c=%4B7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?x=)&c=%4B7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?x=*&c=%4B7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?x=+&c=%4B7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?x=,&c=%4B7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?x=;&c=%4B7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?x=:&c=%4B7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?x=@&c=%4B7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?x=~&c=%4B7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?x=/&c=%4B7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?x=?&c=%4B7QM2-XRD4P => K7QM2XRD4P
renoop://friends/add?x="&c=%4B7QM2-XRD4P => nil
renoop://friends/add?x=<&c=%4B7QM2-XRD4P => nil
renoop://friends/add?x=>&c=%4B7QM2-XRD4P => nil
renoop://friends/add?x=\\&c=%4B7QM2-XRD4P => nil
renoop://friends/add?x=^&c=%4B7QM2-XRD4P => nil
renoop://friends/add?x=`&c=%4B7QM2-XRD4P => nil
renoop://friends/add?x={&c=%4B7QM2-XRD4P => nil
renoop://friends/add?x=|&c=%4B7QM2-XRD4P => nil
renoop://friends/add?x=}&c=%4B7QM2-XRD4P => nil
renoop://friends/add?x=[&c=%4B7QM2-XRD4P => nil
renoop://friends/add?x=]&c=%4B7QM2-XRD4P => nil
renoop://friends/add?x=\u{E9}&c=%4B7QM2-XRD4P => nil
renoop://friends/add?x=\u{1}&c=%4B7QM2-XRD4P => nil
renoop://friends/add?x=\u{7F}&c=%4B7QM2-XRD4P => nil
renoop://friends/add?x=a b&c=%4B7QM2-XRD4P => nil
renoop://friends/add?x=a\tb&c=%4B7QM2-XRD4P => nil
renoop://friends/add?x=%zz&c=%4B7QM2-XRD4P => nil
renoop://friends/add?x=%&c=K7QM2%2DXRD4P => nil
renoop://friends/add?c=K7QM2%2DXRD4P&x=% => nil
https://x/a%zzb/i/K7QM2%2DXRD4P => nil
https://x/a%zzb/%69/K7QM2-XRD4P => nil
https://x/i/K7QM2%2DXRD4P%zz => nil
https://x%41/<>/%69/K7QM2-XRD4P => nil
https://x%41/%69/K7QM2-XRD4P => K7QM2XRD4P
https://x/%69/K7QM2-XRD4P?<#%zz => K7QM2XRD4P
https://\u{E9}/%69/K7QM2-XRD4P => K7QM2XRD4P
https://\u{E9}/<>/%69/K7QM2-XRD4P => nil
https://[!]/i/K7QM2-XRD4P => K7QM2XRD4P
https://[a.b-c_d~e]/i/K7QM2-XRD4P => K7QM2XRD4P
https://[%]/i/K7QM2-XRD4P => K7QM2XRD4P
https://[%41]/i/K7QM2-XRD4P => K7QM2XRD4P
https://[\u{24}&'()*+,;=]/i/K7QM2-XRD4P => K7QM2XRD4P
https://[a@b]/i/K7QM2-XRD4P => nil
https://["]/i/K7QM2-XRD4P => nil
https://[>]/i/K7QM2-XRD4P => nil
https://[\\]/i/K7QM2-XRD4P => nil
https://[^]/i/K7QM2-XRD4P => nil
https://[`]/i/K7QM2-XRD4P => nil
https://[{]/i/K7QM2-XRD4P => nil
https://[|]/i/K7QM2-XRD4P => nil
https://[}]/i/K7QM2-XRD4P => nil
https://[\u{1}]/i/K7QM2-XRD4P => nil
https://[\u{7F}]/i/K7QM2-XRD4P => nil
https://[a b]/i/AAAAA-BBBBB https://c/i/CCCCC-DDDDD => AAAAABBBBB
https://[::1]:80x/i/K7QM2-XRD4P => nil
https://[::1]:99999999999999999999/i/K7QM2-XRD4P => K7QM2XRD4P
https://x:99999999999999999999/i/K7QM2-XRD4P => K7QM2XRD4P
https://x:0/i/K7QM2-XRD4P => K7QM2XRD4P
renoop://friend%C5%BF/add?c=K7QM2-XRD4P => nil
renoop://%EF%BD%86riends/add?c=K7QM2-XRD4P => nil
renoop://[friends]:80/add?c=K7QM2-XRD4P => nil
renoop://Friends:0080/add?c=K7QM2-XRD4P => K7QM2XRD4P
renoop://u:p@FRIENDS/add/?c=K7QM2-XRD4P&c=x#y => K7QM2XRD4P
https://x%2/i/K7QM2-XRD4P => nil
https://x%zz%41/i/K7QM2-XRD4P => nil
https://%/i/K7QM2-XRD4P => nil
https://a%FFb/i/K7QM2-XRD4P => K7QM2XRD4P
https://a%C3b/i/K7QM2-XRD4P => K7QM2XRD4P
https://x:%38%30/i/K7QM2-XRD4P => nil
https://u:p@x:80/i/K7QM2-XRD4P => K7QM2XRD4P
https://u@v:w@x:80/i/K7QM2-XRD4P => K7QM2XRD4P
https://u:w@x:8a/i/K7QM2-XRD4P => nil
https://x/i/K7QM2-XRD4P?a=b&c=d => K7QM2XRD4P
https://x/i/K7QM2-XRD4P/?a => K7QM2XRD4P
https://x/i/K7QM2-XRD4P//#a => K7QM2XRD4P
https://x/i//K7QM2-XRD4P => K7QM2XRD4P
https://x/i/%2F%2F/K7QM2-XRD4P => K7QM2XRD4P
https://x/i/ /K7QM2-XRD4P => nil
https://x/i/%20/K7QM2-XRD4P => nil
https://x/ i/K7QM2-XRD4P => K7QM2XRD4P
https://x/i /K7QM2-XRD4P => nil
https://x/%20i/K7QM2-XRD4P => nil
/i/K7QM2-XRD4P?x#y => K7QM2XRD4P
i/K7QM2-XRD4P# => K7QM2XRD4P
?/i/K7QM2-XRD4P => nil
#/i/K7QM2-XRD4P => nil
%69/K7QM2-XRD4P => K7QM2XRD4P
%zz/i/K7QM2-XRD4P => K7QM2XRD4P
\u{E9}/%69/K7QM2-XRD4P => nil
a\u{E9}:b/i/K7QM2-XRD4P => nil
a[b:c/i/K7QM2-XRD4P => K7QM2XRD4P
]:/i/K7QM2-XRD4P => K7QM2XRD4P
a]:/i/K7QM2-XRD4P => K7QM2XRD4P
a]b:c/i/K7QM2-XRD4P => K7QM2XRD4P
@:/i/K7QM2-XRD4P => K7QM2XRD4P
a@b:c/i/K7QM2-XRD4P => K7QM2XRD4P
(a:/i/K7QM2-XRD4P => nil
{a:/i/K7QM2-XRD4P => nil
\\a:/i/K7QM2-XRD4P => nil
"a:/i/K7QM2-XRD4P => nil
<a:/i/K7QM2-XRD4P => nil
!a:/i/K7QM2-XRD4P => nil
~a:/i/K7QM2-XRD4P => nil
%41:/i/K7QM2-XRD4P => nil
a[:/i/K7QM2-XRD4P => K7QM2XRD4P
[:/i/K7QM2-XRD4P => K7QM2XRD4P
[a]:/i/K7QM2-XRD4P => K7QM2XRD4P
[/i/K7QM2-XRD4P => K7QM2XRD4P
[a:b/i/K7QM2-XRD4P => K7QM2XRD4P
[a:b]c:d/i/K7QM2-XRD4P => K7QM2XRD4P
a:[b]/i/K7QM2-XRD4P => K7QM2XRD4P
[::1]:80/i/K7QM2-XRD4P => K7QM2XRD4P
[::1]x:y/i/K7QM2-XRD4P => K7QM2XRD4P
x[::1]/i/K7QM2-XRD4P => K7QM2XRD4P
[\u{E9}:]/i/K7QM2-XRD4P => K7QM2XRD4P
[ :]/i/K7QM2-XRD4P => K7QM2XRD4P
[<:]/i/K7QM2-XRD4P => K7QM2XRD4P
[%zz:]/%69/K7QM2-XRD4P => nil
[:]/%69/K7QM2-XRD4P => nil
1:/i/K7QM2-XRD4P => nil
-:/i/K7QM2-XRD4P => nil
a-:/i/K7QM2-XRD4P => K7QM2XRD4P
a\u{1}:/i/K7QM2-XRD4P => nil
a\u{7F}:/i/K7QM2-XRD4P => nil
a b:/i/AAAAA-BBBBB c/i/CCCCC-DDDDD => AAAAABBBBB
a\tb:/i/AAAAA-BBBBB c/i/CCCCC-DDDDD => AAAAABBBBB
a:b c/i/AAAAA-BBBBB d/i/CCCCC-DDDDD => CCCCCDDDDD
Add me on reNOOP Friends: https://renoop.duckdns.org/i/K7QM2-XRD4P\nOr enter the code K7QM2-XRD4P on the Friends tab. => K7QM2XRD4P
Or enter the code K7QM2-XRD4P on the Friends tab. => K7QM2XRD4P
Add me on reNOOP Friends: https://renoop.duckdns.org/i/AAAAA-BBBBB\nOr enter the code K7QM2-XRD4P on the Friends tab. => AAAAABBBBB
F\u{FC}g mich bei reNOOP Freunde hinzu: https://renoop.duckdns.org/i/K7QM2-XRD4P\nOder gib den Code K7QM2-XRD4P im Tab \u{201E}Freunde\u{201C} ein. => K7QM2XRD4P
Oder gib den Code K7QM2-XRD4P im Tab \u{201E}Freunde\u{201C} ein. => K7QM2XRD4P
F\u{FC}g mich bei reNOOP Freunde hinzu: https://renoop.duckdns.org/i/AAAAA-BBBBB\nOder gib den Code K7QM2-XRD4P im Tab \u{201E}Freunde\u{201C} ein. => AAAAABBBBB
A\u{F1}\u{E1}deme en Amigos de reNOOP: https://renoop.duckdns.org/i/K7QM2-XRD4P\nO introduce el c\u{F3}digo K7QM2-XRD4P en la pesta\u{F1}a Amigos. => K7QM2XRD4P
O introduce el c\u{F3}digo K7QM2-XRD4P en la pesta\u{F1}a Amigos. => K7QM2XRD4P
A\u{F1}\u{E1}deme en Amigos de reNOOP: https://renoop.duckdns.org/i/AAAAA-BBBBB\nO introduce el c\u{F3}digo K7QM2-XRD4P en la pesta\u{F1}a Amigos. => AAAAABBBBB
Ajoutez-moi dans Amis reNOOP : https://renoop.duckdns.org/i/K7QM2-XRD4P\nOu saisissez le code K7QM2-XRD4P dans l'onglet Amis. => K7QM2XRD4P
Ou saisissez le code K7QM2-XRD4P dans l'onglet Amis. => K7QM2XRD4P
Ajoutez-moi dans Amis reNOOP : https://renoop.duckdns.org/i/AAAAA-BBBBB\nOu saisissez le code K7QM2-XRD4P dans l'onglet Amis. => AAAAABBBBB
Aggiungimi su Amici di reNOOP: https://renoop.duckdns.org/i/K7QM2-XRD4P\nOppure inserisci il codice K7QM2-XRD4P nella scheda Amici. => K7QM2XRD4P
Oppure inserisci il codice K7QM2-XRD4P nella scheda Amici. => K7QM2XRD4P
Aggiungimi su Amici di reNOOP: https://renoop.duckdns.org/i/AAAAA-BBBBB\nOppure inserisci il codice K7QM2-XRD4P nella scheda Amici. => AAAAABBBBB
Dodaj mnie w Znajomych reNOOP: https://renoop.duckdns.org/i/K7QM2-XRD4P\nAlbo wpisz kod K7QM2-XRD4P na karcie Znajomi. => K7QM2XRD4P
Albo wpisz kod K7QM2-XRD4P na karcie Znajomi. => K7QM2XRD4P
Dodaj mnie w Znajomych reNOOP: https://renoop.duckdns.org/i/AAAAA-BBBBB\nAlbo wpisz kod K7QM2-XRD4P na karcie Znajomi. => AAAAABBBBB
Adiciona-me nos Amigos do reNOOP: https://renoop.duckdns.org/i/K7QM2-XRD4P\nOu introduz o c\u{F3}digo K7QM2-XRD4P no separador Amigos. => K7QM2XRD4P
Ou introduz o c\u{F3}digo K7QM2-XRD4P no separador Amigos. => K7QM2XRD4P
Adiciona-me nos Amigos do reNOOP: https://renoop.duckdns.org/i/AAAAA-BBBBB\nOu introduz o c\u{F3}digo K7QM2-XRD4P no separador Amigos. => AAAAABBBBB
\u{414}\u{43E}\u{431}\u{430}\u{432}\u{44C} \u{43C}\u{435}\u{43D}\u{44F} \u{432} \u{434}\u{440}\u{443}\u{437}\u{44C}\u{44F} \u{432} reNOOP: https://renoop.duckdns.org/i/K7QM2-XRD4P\n\u{418}\u{43B}\u{438} \u{432}\u{432}\u{435}\u{434}\u{438} \u{43A}\u{43E}\u{434} K7QM2-XRD4P \u{43D}\u{430} \u{432}\u{43A}\u{43B}\u{430}\u{434}\u{43A}\u{435} \u{AB}\u{414}\u{440}\u{443}\u{437}\u{44C}\u{44F}\u{BB}. => K7QM2XRD4P
\u{418}\u{43B}\u{438} \u{432}\u{432}\u{435}\u{434}\u{438} \u{43A}\u{43E}\u{434} K7QM2-XRD4P \u{43D}\u{430} \u{432}\u{43A}\u{43B}\u{430}\u{434}\u{43A}\u{435} \u{AB}\u{414}\u{440}\u{443}\u{437}\u{44C}\u{44F}\u{BB}. => K7QM2XRD4P
\u{414}\u{43E}\u{431}\u{430}\u{432}\u{44C} \u{43C}\u{435}\u{43D}\u{44F} \u{432} \u{434}\u{440}\u{443}\u{437}\u{44C}\u{44F} \u{432} reNOOP: https://renoop.duckdns.org/i/AAAAA-BBBBB\n\u{418}\u{43B}\u{438} \u{432}\u{432}\u{435}\u{434}\u{438} \u{43A}\u{43E}\u{434} K7QM2-XRD4P \u{43D}\u{430} \u{432}\u{43A}\u{43B}\u{430}\u{434}\u{43A}\u{435} \u{AB}\u{414}\u{440}\u{443}\u{437}\u{44C}\u{44F}\u{BB}. => AAAAABBBBB
\u{5728} reNOOP \u{597D}\u{53CB}\u{4E2D}\u{6DFB}\u{52A0}\u{6211}\u{FF1A} https://renoop.duckdns.org/i/K7QM2-XRD4P\n\u{6216}\u{5728}\u{201C}\u{597D}\u{53CB}\u{201D}\u{6807}\u{7B7E}\u{9875}\u{8F93}\u{5165}\u{4EE3}\u{7801} K7QM2-XRD4P \u{3002} => K7QM2XRD4P
\u{6216}\u{5728}\u{201C}\u{597D}\u{53CB}\u{201D}\u{6807}\u{7B7E}\u{9875}\u{8F93}\u{5165}\u{4EE3}\u{7801} K7QM2-XRD4P \u{3002} => K7QM2XRD4P
\u{5728} reNOOP \u{597D}\u{53CB}\u{4E2D}\u{6DFB}\u{52A0}\u{6211}\u{FF1A} https://renoop.duckdns.org/i/AAAAA-BBBBB\n\u{6216}\u{5728}\u{201C}\u{597D}\u{53CB}\u{201D}\u{6807}\u{7B7E}\u{9875}\u{8F93}\u{5165}\u{4EE3}\u{7801} K7QM2-XRD4P \u{3002} => AAAAABBBBB
# display
K7QM2XRD4P => K7QM2-XRD4P
0123456789 => 01234-56789
 => 
ABC => ABC
K7QM2-XRD4P => K7QM2-XRD4P
k7qm2xrd4p => k7qm2-xrd4p
ABCDEFGHJKM => ABCDEFGHJKM
# pageLink
https://renoop.duckdns.org K7QM2XRD4P => https://renoop.duckdns.org/i/K7QM2-XRD4P
http://127.0.0.1:8787 0123456789 => http://127.0.0.1:8787/i/01234-56789
https://example.org/friends K7QM2XRD4P => https://example.org/friends/i/K7QM2-XRD4P
https://example.org short => https://example.org/i/short
# signingString
put /v2/me/days/2026-10-10 1791540000 AAAAAAAAAAAAAAAAAAAAAA a59ed6f5a3416c9b116d6d17ca709ffab4e839735f21ea3c8d301a045f567445 => renoop-friends-v2\nPUT\n/v2/me/days/2026-10-10\n1791540000\nAAAAAAAAAAAAAAAAAAAAAA\na59ed6f5a3416c9b116d6d17ca709ffab4e839735f21ea3c8d301a045f567445
GET /v2/feed?days=7 1791540000 AAAAAAAAAAAAAAAAAAAAAA e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855 => renoop-friends-v2\nGET\n/v2/feed?days=7\n1791540000\nAAAAAAAAAAAAAAAAAAAAAA\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
Delete /v2/claims/mine 0 abc_DEF-0123456789 e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855 => renoop-friends-v2\nDELETE\n/v2/claims/mine\n0\nabc_DEF-0123456789\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
patch /v2/me -5 n\u{20} => renoop-friends-v2\nPATCH\n/v2/me\n-5\nn\n
post /v2/users/00000000000000aa/days?days=14 4102444800 zzzzzzzzzzzzzzzz 00 => renoop-friends-v2\nPOST\n/v2/users/00000000000000aa/days?days=14\n4102444800\nzzzzzzzzzzzzzzzz\n00
# strapHandle
whoop-4A0123456 => renoop-friends-strap-v1\nwhoop-4A0123456
whoop-4A0123456 => 98c15f4b6c7ad639bba026d0352acab84406af76243690aac8b169a1a902f707
oura-4A0123456 => renoop-friends-strap-v1\noura-4A0123456
oura-4A0123456 => 54c59e31b24fd54ff9e7949f9d6eb65e278d87c7998f7bfcce1cde7733e4c7b1
whoop-4a0123456 => renoop-friends-strap-v1\nwhoop-4a0123456
whoop-4a0123456 => d3443958518164f58e4fae685e77ddf1da841a6b41eb37a9c09dfcc9ed76d458
whoop- => renoop-friends-strap-v1\nwhoop-
whoop- => 990f7ac8af5ce7e29db80fe3e0c57e9f49d2f728808fa7dd5fe669d7f2dfe7e2
 => renoop-friends-strap-v1\n
 => fa4f4f8f120dc90c144858043e8fe9e12f0e0b69da5933c3cf3fe72debcf8aa5
oura-2H3B2405003655 => renoop-friends-strap-v1\noura-2H3B2405003655
oura-2H3B2405003655 => 4647fa8ee20629cba0a4aa67db5a52b6b8e1ca34aab76709b0dd92519d817fbe
whoop-5AM0123456789ABCDEF => renoop-friends-strap-v1\nwhoop-5AM0123456789ABCDEF
whoop-5AM0123456789ABCDEF => 57399cb874bdfe1126652d9c0490637a4577b17463c7b85e7a0ec1ce52180bdb
whoop-\u{421}\u{415}\u{420} => renoop-friends-strap-v1\nwhoop-\u{421}\u{415}\u{420}
whoop-\u{421}\u{415}\u{420} => 8c3fe8b1c2d9f52d563bb236661ee1a846947b2e9d92deacf329a2c5f707ac08
# sha256
 => e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
{} => 44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a
{"recovery":81} => a59ed6f5a3416c9b116d6d17ca709ffab4e839735f21ea3c8d301a045f567445
{ } => 257c1be96ae69f4b01c2c69bdb6d78605f59175819fb007d0bf245bf48444c4a
\u{410}\u{43D}\u{43D}\u{430} \u{1F642} => 7c680b7783013691d6dfff8215ef3f247782d390928964c609227699440f9e20
# indented
{"name":"a\\"b{,}","strain":38.6,"e":1e-7,"days":[[1791539880,64]],"n":null} => {\n  "name": "a\\"b{,}",\n  "strain": 38.6,\n  "e": 1e-7,\n  "days": [\n    [\n      1791539880,\n      64\n    ]\n  ],\n  "n": null\n}
{"a":[],"b":{},"c":[{}]} => {\n  "a": [],\n  "b": {},\n  "c": [\n    {}\n  ]\n}
{ "a" :\t[ ] ,\r\n "b" : " x : y,\\t[z] \\\\" } => {\n  "a": [],\n  "b": " x : y,\\t[z] \\\\"\n}
{"\u{438}\u{43C}\u{44F}":"\u{410}\u{43D}\u{43D}\u{430} \u{1F642}","n":12.0,"big":100000000000000000000} => {\n  "\u{438}\u{43C}\u{44F}": "\u{410}\u{43D}\u{43D}\u{430} \u{1F642}",\n  "n": 12.0,\n  "big": 100000000000000000000\n}
38.6 => 38.6
 => 
]} => \n]\n}
<html>busy</html> => <html>busy</html>
{"open": => {\n  "open":\u{20}
"unterminated => "unterminated
}{ => \n}{\n \u{20}
[] => []
{} => {}
[[]] => [\n  []\n]
[[],[]] => [\n  [],\n  []\n]
{"a":{"b":{"c":[1,2,{"d":null}]}}} => {\n  "a": {\n    "b": {\n      "c": [\n        1,\n        2,\n        {\n          "d": null\n        }\n      ]\n    }\n  }\n}
[ 1 , 2 ] => [\n  1,\n  2\n]
[1,2,] => [\n  1,\n  2,\n  \n]
{"a":"\\\\","b":"\\""} => {\n  "a": "\\\\",\n  "b": "\\""\n}
{"a":"x\\\\"} => {\n  "a": "x\\\\"\n}
}}}]]] => \n}\n}\n}\n]\n]\n]
[[[{{{ => [\n  [\n    [\n      {\n        {\n          {\n           \u{20}
{"a":true,"b":false} => {\n  "a": true,\n  "b": false\n}
{"a" : [ { } , [ ] ] } => {\n  "a": [\n    {},\n    []\n  ]\n}
{"a":[\n]} => {\n  "a": []\n}
"a:b,c" => "a:b,c"
: , : => : ,\n:\u{20}
{"a":"\u{A0}x\u{2028}"} => {\n  "a": "\u{A0}x\u{2028}"\n}
{\u{A0}"a":1} => {\n  \u{A0}"a": 1\n}
{"me":{"id":"00000000000000aa","name":"Anna","avatarRev":0},"devices":[{"id":"k","platform":"android","addedAt":1,"lastSeenAt":2,"probationUntil":null}],"days":[{"day":"2026-10-10","strain":38.6,"workouts":[{"strain":35.2,"kcal":12.0}]}]} => {\n  "me": {\n    "id": "00000000000000aa",\n    "name": "Anna",\n    "avatarRev": 0\n  },\n  "devices": [\n    {\n      "id": "k",\n      "platform": "android",\n      "addedAt": 1,\n      "lastSeenAt": 2,\n      "probationUntil": null\n    }\n  ],\n  "days": [\n    {\n      "day": "2026-10-10",\n      "strain": 38.6,\n      "workouts": [\n        {\n          "strain": 35.2,\n          "kcal": 12.0\n        }\n      ]\n    }\n  ]\n}
# address
https://renoop.duckdns.org => ok https://renoop.duckdns.org
\u{20} https://renoop.duckdns.org/ \u{20} => ok https://renoop.duckdns.org
HTTPS://ReNoop.DuckDNS.org => ok https://renoop.duckdns.org
https://example.org:443 => ok https://example.org
https://example.org:8443/ => ok https://example.org:8443
https://example.org/friends/ => ok https://example.org/friends
renoop.duckdns.org => ok https://renoop.duckdns.org
example.org:8443 => ok https://example.org:8443
http://renoop.duckdns.org => refused notHTTPS
http://192.168.1.10:8787 => refused notHTTPS
http://10.0.2.2:8787 => refused notHTTPS
http://localhost.example.org => refused notHTTPS
http://127.0.0.1.example.org => refused notHTTPS
http://127.0.0.2:8787 => refused notHTTPS
ftp://example.org => refused notHTTPS
http://localhost:8787 => ok http://localhost:8787
http://127.0.0.1:8787/ => ok http://127.0.0.1:8787
http://LOCALHOST:80 => ok http://localhost
https://localhost:8443 => ok https://localhost:8443
https://user:pass@example.org => refused hasCredentials
https://example.org/?token=1 => refused hasQuery
https://example.org/#top => refused hasQuery
\u{20} \u{20} => refused empty
 => refused empty
https:// => refused malformed
https://exa mple.org => refused malformed
https://example.org:99999 => refused malformed
https://\u{434}\u{43E}\u{43C}\u{435}\u{43D}.\u{440}\u{444} => refused malformed
https://xn--d1acufc.xn--p1ai => ok https://xn--d1acufc.xn--p1ai
\u{440}\u{435}\u{435}\u{437}\u{416}//127\u{44E}0\u{44E}0\u{44E}1\u{416}8787 => refused malformed
https://example.org// => ok https://example.org
https://example.org/a//b/ => ok https://example.org/a//b
https://example.org/a/b => ok https://example.org/a/b
https://example.org:0 => refused malformed
https://example.org: => ok https://example.org
https://example.org:65535 => ok https://example.org:65535
https://example.org:65536 => refused malformed
https://example.org:08443 => ok https://example.org:8443
https://example.org:+80 => refused malformed
https://example.org:8a => refused malformed
http://[::1]:8787 => refused notHTTPS
https://[::1]:8443 => ok https://[::1]:8443
https://[::1] => ok https://[::1]
https://[] => refused malformed
https://example_org => refused malformed
https://-example.org => refused malformed
https://.example.org => refused malformed
https://example.org. => ok https://example.org.
https://example..org => ok https://example..org
https://EXAMPLE.org/Friends => ok https://example.org/Friends
https://user@example.org => refused hasCredentials
https://example.org?x => refused hasQuery
https://example.org#x => refused hasQuery
https://example.org/a?x#y => refused hasQuery
http://localhost => ok http://localhost
http://localhost:80/x/ => ok http://localhost/x
http://127.0.0.1 => ok http://127.0.0.1
http://127.0.0.1:65536 => refused malformed
localhost:8787 => ok https://localhost:8787
127.0.0.1:8787 => ok https://127.0.0.1:8787
http://Localhost:8787/ => ok http://localhost:8787
htp://example.org => refused notHTTPS
1https://example.org => refused malformed
https:/example.org => ok https://https/example.org
https:example.org => refused malformed
://example.org => refused malformed
https://example.org/a b => refused malformed
https://example.org/%20 => ok https://example.org/%20
https://example.org/a%2Fb/ => ok https://example.org/a%2Fb
https://example.org/\u{43F} => refused malformed
https://example.org/"x" => refused malformed
https://example.org/<x> => refused malformed
https://exa\tmple.org => refused malformed
https://example.org\n => ok https://example.org
\nhttps://example.org => ok https://example.org
https://example.org:443/ => ok https://example.org
http://localhost:080 => ok http://localhost
https://127.0.0.1 => ok https://127.0.0.1
https://1.2.3.4:8787 => ok https://1.2.3.4:8787
https://a => ok https://a
a => ok https://a
a.b/c/ => ok https://a.b/c
a/b => ok https://a/b
a:b => refused malformed
a:80 => ok https://a:80
a:80/b => ok https://a:80/b
@a => refused hasCredentials
a@b => refused hasCredentials
a@b:80 => refused hasCredentials
https://a:b@c:d => refused hasCredentials
https://example.org:80 => ok https://example.org:80
http://localhost:443 => ok http://localhost:443
https://example.org/~user/ => ok https://example.org/~user
https://example.org/a;b => ok https://example.org/a;b
https://example.org/a\\b => refused malformed
https://example.org/{a} => refused malformed
https://example.org/a|b => refused malformed
https://example.org/a^b => refused malformed
https://example.org/a`b => refused malformed
ws://example.org => refused notHTTPS
https+x://example.org => refused notHTTPS
h.t-t+p://example.org => refused notHTTPS
https://example.org:1 => ok https://example.org:1
https://example.org:000000443 => ok https://example.org
https://example.org:0000000443 => refused malformed
"""

    /** The lines of one section, in the oracle's order. */
    fun section(name: String): List<String> {
        val lines = STDOUT.split("\n")
        val start = lines.indexOf("# $name")
        require(start >= 0) { "no section $name" }
        return lines.drop(start + 1).takeWhile { !it.startsWith("# ") && it.isNotEmpty() }
    }

    /** The input of a line, as the Swift side was given it. */
    fun input(line: String): String = unescape(line.substring(0, line.indexOf(" => ")))

    /** A line as the oracle prints it for [input] and [answer]. */
    fun line(input: String, answer: String?): String = escape(input) + " => " + (answer?.let(::escape) ?: "nil")

    fun unescape(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '\\' || i + 1 >= text.length) {
                out.append(c)
                i++
                continue
            }
            when (text[i + 1]) {
                'n' -> { out.append('\n'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                'u' -> {
                    val close = text.indexOf('}', i)
                    out.appendCodePoint(text.substring(i + 3, close).toInt(16))
                    i = close + 1
                }
                else -> error("bad escape in $text")
            }
        }
        return out.toString()
    }

    fun escape(text: String): String {
        val out = StringBuilder()
        val scalars = text.codePoints().toArray()
        scalars.forEachIndexed { index, cp ->
            when {
                cp == '\\'.code -> out.append("\\\\")
                cp == '\n'.code -> out.append("\\n")
                cp == '\r'.code -> out.append("\\r")
                cp == '\t'.code -> out.append("\\t")
                cp == 0x24 -> out.append("\\u{24}")
                cp == ' '.code && (index == 0 || index == scalars.size - 1) -> out.append("\\u{20}")
                cp < 0x20 || cp >= 0x7F -> out.append("\\u{").append(cp.toString(16).uppercase()).append('}')
                else -> out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }
}
