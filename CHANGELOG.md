# What is new in WalletTracker

One section for each version, from the newest. This is the English translation of [NOVITA.md](NOVITA.md): the two
files have the same sections, and when a version is published (docs/50) its text becomes the description of the
release on GitHub. The numbers follow the MAJOR.MINOR.PATCH rule: `1.0.1` fixes, `1.1.0` adds something, `2.0.0`
changes something important for those who use it.

## 1.3.1

- **Revolut and pending payments**: for banks that do not give the booked balance (like Revolut) the app now accounts
  for the pending payments included in the balance, and does not count them a second time once they are booked: no
  more daily "the transactions do not match" notice, and no payments counted twice. The balance includes the pending
  payments, as in the bank's app and as the expenses do; on the Accounts page, when there are any, a line also shows
  the booked balance. The same applies to a balance you write for a linked account.
- **Fewer false notices from the balance check**: when a bank takes a payment off the balance before showing the
  transaction, the app now recognises it and does not report a difference that is not there. If the bank says which
  transaction is the last one in the balance, the balance shown does not count it twice either.
- **Rules on the bank's code**: some banks send with the transaction a code that says what operation it is (for example
  ING "Accredito Stipendio/Pensione"). Now a rule can look at that code instead of the text: in the Rules form choose
  "Search in the bank's transaction code". The code is shown in the transaction sheet, under Details, with "create a
  rule". New starter rules: the salary goes to *Salary and pension*, the card withdrawal to *Cash and withdrawals*,
  the bank's bonuses and the Revolut rewards to *Tax refunds and bonuses*, even when the description does not say so.
- **Interest paid**: the interest you pay to the bank (an overdraft, a credit line) goes to *Bank charges*. Before, it
  stayed an expense without a category.
- **The cash chart, easier to read and without jumps**: it starts with the last 3 months, with the 1M, 3M and 1Y buttons
  (the choice is remembered); over a whole year a day was less than a pixel wide, and the first saved days could not be
  seen. Today's dot is smaller and no longer covers the end of the line. Tapping an account, the previous chart stays
  until the new one arrives, which appears with a short fade: the card does not change height and the page no longer
  jumps (before, the chart disappeared for a moment and everything below it went up and down). Sliding along the chart
  of one account, the chart stays still too; above it, the balance of the chosen day or how much the period changed
  ("+€1,250 since 22 Aug").
- **The bottom bar no longer shakes while sliding along the charts on the phone**: near the edge, the label of the day
  went a few pixels off the screen, and the bar moved at every day. Now the label always stays inside the chart.
- **Fewer jumps across the app**: changing month in the Overview, the sentence under the figure no longer changes shape
  before the numbers of the month arrive (it went from two lines to one and the page went up, then down again). In
  Budget the "New budget" form appears together with the list, under it: before, it appeared at the top and was pushed
  down at once when the budgets arrived. The notices at the top of the page (a new version, the backups…) open with a
  short animation instead of pushing everything down in one go.
- **Overview: the things to fix higher up.** The "transactions to review" and "suggested categories" boxes come right
  after the expenses by category: on the phone before the cash, on the computer above the investments. Before, they
  were at the bottom of the page.

## 1.3.0

- **WalletTracker in English too**: in Settings → Language the app switches between Italian and English, at any time and
  in both directions. It applies to the whole installation, the computer and the phone. Dates, numbers and amounts follow
  the language; the starter categories you have not renamed take their English name ("Groceries"). What comes from
  outside or from you stays as it is: the texts of the transactions sent by the banks, the starter rules, the
  categories you renamed, your notes.
- **The installation asks for the language**: at the first start the script asks "Lingua / Language", and from then on
  it speaks that one only; the app opens in the same language. In the ZIP there is the **English** folder, with the
  instructions in English and "Start / Update / Backup WalletTracker" to double-click. The guide is in English too:
  GUIDE.md.
- **Import Excel files too**: the file exported by the bank (for example Fineco's "Esporta in excel") is loaded as it
  is, without converting it. The app finds the table by itself even when there are lines of notes above it.
- **Complete the history of the linked accounts**: when you link an account, the bank gives the transactions of the
  last few months only. Now you can load the bank's file into a linked account too: the app adds **only the
  transactions older** than the ones that came from the bank, and skips the others, so no duplicates are created. Before,
  this was possible only with Trade Republic.
- **Linking a new account: the app explains what to do.** Your Enable Banking application is in restricted mode and
  passes to the app only the accounts of its list: an account opened later must first be added there ("Link
  accounts"). Now Settings and "Link a bank" say so with the steps, and if no account arrives on the way back from the
  bank the page explains why instead of writing "0 accounts".
- **Two different accounts are never confused** when linking, even if Enable Banking gave them the same fingerprint.
- **Remove a connection without losing the history**: in Accounts, the "Bank connections" section shows every linked
  bank, including those with an expired consent or without accounts, with "Remove connection". The app closes the
  consent on Enable Banking and stops reading those accounts, but **the accounts and the transactions stay**. If one
  day you link the bank again, you find the same accounts, without duplicate transactions. The connections without
  accounts (the attempts with a bank not yet in the Enable Banking list) are deleted instead.
- **The Trade Republic file is imported into any account**: the Import page recognises the Trade Republic transaction
  export and reads it by itself, without choosing the columns. The operations on securities (transferred shares, rights
  of a capital increase), which do not move money, are skipped instead of giving "the amount is missing"; the amounts
  are the ones you see on the account, with taxes and fees (the net interest, the stamp duty with its amount). In a
  **linked** Trade Republic account it also completes the counterparty and the description of the transactions that
  came from the bank without text: one upload is enough.
- **One page for the files: "Complete the transactions" is inside Import.** With the Trade Republic export, Import
  loads the transactions not yet present and, if the account is linked, completes with description and counterparty
  the ones received from the bank. The rows that find no match are listed in "To review". The old address leads to
  Import.
- **No transaction lost just before the bank's history**: with the Trade Republic file, a row from the 3 days before
  the first transaction that came from the bank was not added, even when the bank did not have it. Now it is added,
  and the same operation with a shifted date is recognised as before, without duplicates.
- **The notices can be closed**: every notice (Overview, Investments, strips at the top) has a small cross. Once
  closed, it stays closed on that device until the situation changes: one more budget exceeded, another account,
  another backup problem make it appear again.
- **No more "only the transfers are visible" notice for a securities account that receives coupons or dividends**: it
  happened with a securities account whose securities came from another account of yours, without purchases of its
  own. Now the notice appears only for an account of which really only the transfers are visible.
- **The Import page says what it will do**: choosing the account (manual or linked) you see at once whether it will add
  all the rows or only the older months; once the file is loaded, how many rows are added and why the others are not.
- **The Overview also shows how much you spent in the year**: next to the figure of the month, in small type, the total
  since the start of the year. Looking at a past month it counts up to the end of that month ("Jan–Mar 2025", "All of
  2025").
- **The Trade Republic guide in Import** is a card to open like the one in "Link a bank", separate from the box below.
- **Changing month, the Overview chart is drawn only once**: before, it started twice, the first time still with the
  numbers of the month you left.
- **Investments: the income of a single month.** In the "Coupons, dividends and interest" card, above the "By type"
  and "From where" lists, a switch chooses whether to show the income of the whole period (as before) or only that of
  the month of the highlighted bar. Tapping another bar the lists follow it, and the links to Transactions open that
  month.
- **The cash chart**: in the Overview, the "Cash" card shows how the money on the accounts has changed, day by day, up
  to one year. From now on the app saves the balance of every account every day (solid line); for the days before, it
  estimates it by taking the transactions off the next real balance (dashed line, "estimate"). Sliding along the chart,
  the total shows that day. With accounts in several currencies, buttons above the chart choose which one to draw (one
  at a time, without conversions). Tapping an account in the list, the chart shows only that account, to compare it
  with its statement. You get to Accounts with "See the accounts", at the top of the card.

- **The balance of the linked accounts is always up to date**: the bank gives the balance at most once a day, the
  transactions more often. Now a transaction that arrives after the last balance (for example a coupon) is added at once
  to the balance in the Accounts page, in the Overview and in the cash chart, instead of waiting for the bank to give
  the balance again.
- **The balance check**: every time the bank gives a new balance (or you write one), the app checks that it matches the
  previous balance plus the transactions that arrived in between. If it does not, a notice in the Overview says which
  account, in which period and by how much, with the transactions to check: usually a transaction is missing, and it
  can be recovered by loading the bank's file in Import. When it arrives, the notice disappears by itself.

Already using WalletTracker? Nothing changes: the app and the scripts stay in Italian until you choose otherwise.

## 1.2.7

- **Coupons and dividends really net, security by security**: Fineco's withholdings ("Rit.ced.su", "Rit.div.su") were
  not matched to their coupon or dividend, and all ended up in the "Unmatched withholdings" row. The total was right,
  but the "Coupons" and "Dividends" rows and the securities were gross. Now every withholding is taken off its own
  income, including that of the interest on cash, and the matching also works with banks that write the texts in
  another way (with the ISIN code of the security).
- **Moving securities between your accounts is no longer a tax**: the "Ritenuta Fiscale … Qta/Val.nom." rows that
  Fineco writes when you move securities from one of your accounts to another (a debit on one, the same credit on the
  other) are now internal transfers. They no longer touch the net income and do not appear as a cost, not even when
  the app has one side only.
- **The same security from two banks is a single row** in "From where" (for example Fineco's "ENEL" and Trade
  Republic's "Enel").
- **The note under the invested amount says what the sales contain**: they are as the bank records them, and the
  capital gains tax that the bank pays separately is in "Other taxes".
- The row that remains for the withholdings without their coupon in the period is called "Withholding tax without its
  income in the period" and no longer leads to a wrong list.

The update fixes by itself the rows you already have, but not the ones whose type you chose by hand.

## 1.2.6

- **Withholdings have a type of their own, "Withholding tax on income"**: before, they were a tax with the
  "Ritenute" category, and if you renamed that category Investments no longer recognised them and showed the income
  higher than it really was. Now the figure is right whatever the names of your categories. The other taxes (stamp
  duty, capital gains, Tobin tax) are called "Other investment taxes".
- **To see the withholdings paid**: in Investments as before (gross, withholdings, net), and in Transactions with the
  filter Type → "Withholding tax on income".

The update fixes by itself the withholdings you already have, including the ones chosen by hand, and the rules; after
that the "Ritenute" category is no longer needed and disappears (it stays only if you had put a budget on it).

## 1.2.5

(1.2.4 was not published: the release stopped because these notes were missing.)

- **No "empty" backups**: if the database does not have the WalletTracker tables yet (an installation just created),
  the backup is not made, and the app says so. This way a backup without your data never takes the place of a good one.
- **On the phone "This year" stays in its box**: in Investments it came out of the button, and on the narrowest phones
  so did "Week" in Analysis.

## 1.2.3

- **The backups no longer grow forever**: the last 14 automatic ones, 10 made by hand and 3 from before each update or
  restore are kept. In Settings → Backup you see 5 of them, with "Show all", and the dates in a column.

The extra backups are removed by themselves, each at the first new backup of its type after the update.

## 1.2.2

- **Overview**: "Where it goes" is now called **"Expenses by category"**; on the phone the card is right below the
  chart, and the cash after it.

## 1.2.1

- **"Update" works even when the GitHub service for the versions does not answer**: the script tries again by itself,
  and if needed reads the latest version from the normal GitHub page. The ZIP is checked with its checksum, as always.
- **The errors say the reason**: the GitHub code and what it means, instead of "check the connection".
- **Touches to "Update"**: on Windows the confirmation question lacked the version number; and what is new is read
  without the formatting marks (`**`).

Those who have already chosen the backup folder are not asked the question: the script asks only those who never chose
it.

## 1.2.0

- **Choose where to save the backups**: at the first start the script asks for the folder (an external disk, or the
  Google Drive folder on the computer), and to change it later there is `installa.ps1 -BackupFolder`
  (`./installa.sh --backup-folder`). No more `.env` to edit by hand.
- **The app shows where the backups are**: in Settings → Backup, with the command to change folder.
- **The guide**: how to keep the backups on Google Drive ("Mirror files"), chapter 8.

Already on 1.1.1? Double-click **Update WalletTracker**: once only, it will ask you where to save the backups (Enter
leaves the current one, the **backup** folder). If you choose another one, the backups already made stay where they
are: move them yourself.

## 1.1.1

- **Linking the banks from the ZIP works**: Enable Banking wants an https address, and now the app uses the Tailscale
  one even when you open it from the server (http://localhost:8000). On the way back from the bank the Tailscale
  address opens, the app asks for the PIN and finishes the connection. Before, the bank sent you back to a page that
  did not answer.
- **Without Tailscale the app says so first**: in "Link a bank" and in Settings, with what to do, instead of stopping
  after the consent at the bank.
- **More compact Investments**: the long lists ("From where", with one security per row) show the first 5 rows, with
  "Show all" and "Show less"; the cash is below the transfers, and the page no longer leaves empty spaces.
- **The guide**: Tailscale before the banks, because it is also needed to link them.

Already on 1.1.0? Double-click **Update WalletTracker**. If you had registered only
`https://localhost:8443/callback` on Enable Banking, add the Tailscale address you find in Settings.

## 1.1.0

- **Update with a double click** ("Update WalletTracker"): the app moves to the latest version by itself, with a
  backup first; if the new version does not start, it goes back by itself to the previous one, with the previous data.
- **The app tells you when a new version is out**: a strip at the top, and what is new in Settings → About.
- **Start, Update and Backup with a double click**, on Windows and on the Mac: no terminal and no commands.
- **Docker Desktop off?** The script starts it by itself, and at the end says whether WalletTracker will restart by
  itself when the computer is turned on.
- **Touches**: capital letters at the start of the line throughout the app, no box after a click on "Change the logo",
  the actions that no longer wrap in the middle; "create a rule" in a transaction without a category is a link.

**From this version on** you update with "Update WalletTracker". To get there from 1.0.0, once only, download the ZIP
of 1.1.0 from the releases page (`docs/50`, part S).

## 1.0.0

The first published version.

- **Your accounts**, read from the banks with Enable Banking or imported from CSV files (Trade Republic and Tinaba
  too), classified by rules, suggestions and, if you like, by artificial intelligence.
- **Overview, Transactions, Analysis, Budget, Subscriptions and Investments**, designed for the phone.
- **A PIN** to open the app (optional from the computer it is installed on), with the recovery code.
- **From the phone**, anywhere, with Tailscale and a real https; the app installs like a real app.
- **Backups** encrypted with a password of yours, every night by themselves, and "Make a backup now" from Settings.
