import { isUuid, pathSegment, tokenFromLink } from './paths';

describe('isUuid', () => {
  it('accepts the shape the contract issues, in either case', () => {
    expect(isUuid('00000000-0000-4000-8000-0000000000a1')).toBe(true);
    expect(isUuid('00000000-0000-4000-8000-0000000000A1')).toBe(true);
  });

  it('rejects everything a member could paste or a deep link could carry', () => {
    expect(isUuid('')).toBe(false);
    expect(isUuid('b1')).toBe(false);
    expect(isUuid('../accounts/00000000-0000-4000-8000-0000000000a1')).toBe(false);
    expect(isUuid('00000000-0000-4000-8000-0000000000a1/../accounts')).toBe(false);
    expect(isUuid('00000000-0000-4000-8000-0000000000zz')).toBe(false);
    expect(isUuid(' 00000000-0000-4000-8000-0000000000a1 ')).toBe(false);
  });
});

describe('pathSegment', () => {
  it('leaves a real id untouched, so every existing path is byte-identical', () => {
    expect(pathSegment('00000000-0000-4000-8000-0000000000a1')).toBe(
      '00000000-0000-4000-8000-0000000000a1'
    );
  });

  it('escapes the separators a traversal payload needs', () => {
    expect(pathSegment('../accounts/x')).toBe('..%2Faccounts%2Fx');
    expect(pathSegment('a/b')).not.toContain('/');
    expect(pathSegment('?q=1&x=2')).toBe('%3Fq%3D1%26x%3D2');
  });
});

// Step 215, family E. Two of this contract's tokens reach a member as a *web*
// URL rather than as a token — `{publicUrl}/reset-password/{token}` and
// `{publicUrl}/invite/{token}` — because the server has one public origin and
// no idea a phone app exists. So the member pastes a link and the app has to
// find the token in it rather than asking them to.
describe('tokenFromLink', () => {
  it('takes the token out of a whole reset link', () => {
    expect(tokenFromLink('https://trove.example.test/reset-password/RESET-TOKEN')).toBe(
      'RESET-TOKEN'
    );
  });

  it('takes the token out of a whole invite link', () => {
    expect(tokenFromLink('https://trove.example.test/invite/INVITE-TOKEN')).toBe('INVITE-TOKEN');
  });

  it('accepts a bare token pasted on its own', () => {
    expect(tokenFromLink('RESET-TOKEN')).toBe('RESET-TOKEN');
  });

  it('survives a trailing slash and surrounding whitespace', () => {
    expect(tokenFromLink('  https://trove.example.test/reset-password/RESET-TOKEN/  ')).toBe(
      'RESET-TOKEN'
    );
  });

  // A pasted link that picked up a tracking parameter would otherwise send
  // the query to the server as part of the token. On a route whose only
  // answer is one undifferentiated "not valid", that is indistinguishable
  // from a spent link — so it would read as the admin's fault.
  it('drops a query string and a fragment rather than sending them as token', () => {
    expect(tokenFromLink('https://trove.example.test/reset-password/TOKEN?utm=mail')).toBe('TOKEN');
    expect(tokenFromLink('https://trove.example.test/reset-password/TOKEN#top')).toBe('TOKEN');
  });

  it('is empty for an empty paste', () => {
    expect(tokenFromLink('   ')).toBe('');
    expect(tokenFromLink('///')).toBe('');
  });

  // Extraction, not validation: the call site still encodes it.
  it('does not encode — that is pathSegment’s job at the call site', () => {
    expect(tokenFromLink('../admin')).toBe('admin');
    expect(pathSegment(tokenFromLink('a b'))).toBe('a%20b');
  });
});
