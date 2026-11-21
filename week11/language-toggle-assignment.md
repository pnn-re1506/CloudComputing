# Assignment: Web app with language toggle: English and Vietnamese

**Opened:** Monday, 16 November 2026, 8:45 AM  
**Due:** Monday, 23 November 2026, 12:00 AM

Build a web page with the following functionality.

## Access

- The page requires the user to log in with a secret token before showing anything else.
- A wrong token must not grant access; a correct token unlocks the rest of the page.

## Uploading files

- The user can choose a file from their computer and upload it, along with a short text note describing the file.

## Viewing the file list

- The page shows every uploaded file in a list, including at least: the file's name, its size, when it was last modified, and its note.
- Each file in the list can be downloaded.
- Each file in the list can be deleted. Deleting a file also removes its note.

## Language toggle

- The page has a single button that switches the entire page between English and Vietnamese.
- Switching language changes every piece of fixed text on the page (titles, button labels, column headers, confirmation messages) and switches every file's displayed note to the matching language.
- The note the user types when uploading is always written in English, regardless of which language the page is currently displaying.

## General expectations

- The page should accurately reflect the real, current state of the uploaded files at all times — after an upload, a delete, or a language switch, what's on screen should match reality without a manual page refresh.
- If something goes wrong (a failed upload, a failed delete, a login that is rejected), the user should see a clear indication that it failed, not a silent failure or a broken-looking page.
