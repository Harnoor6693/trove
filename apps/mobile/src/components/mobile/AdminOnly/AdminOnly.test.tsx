import React from 'react';
import { Text } from 'react-native';
import { AxiosError } from 'axios';
import { render, screen } from '@testing-library/react-native';
import { AdminOnly } from './AdminOnly';

function refusal(status: number, slug: string) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const config = { headers: {} } as any;
  return new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, {
    data: {
      success: false,
      error: {
        type: `https://trove.finance/errors/${slug}`,
        title: slug,
        status,
        detail: 'Refused.',
      },
    },
    status,
    statusText: '',
    headers: {},
    config,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any);
}

it('renders the console when nothing was refused', async () => {
  await render(
    <AdminOnly error={null}>
      <Text>console</Text>
    </AdminOnly>
  );
  expect(screen.getByText('console')).toBeTruthy();
  expect(screen.queryByTestId('admin-forbidden')).toBeNull();
});

it('renders a clean state — not an error toast — for the 403 forbidden a MEMBER gets', async () => {
  await render(
    <AdminOnly error={refusal(403, 'forbidden')}>
      <Text>console</Text>
    </AdminOnly>
  );
  expect(screen.getByTestId('admin-forbidden')).toBeTruthy();
  expect(screen.getByTestId('admin-forbidden-title').props.children).toBe('Administrators only');
  expect(screen.queryByText('console')).toBeNull();
});

it('does not swallow the other 403 — step-up-required on an admin route means a SELF target', async () => {
  await render(
    <AdminOnly error={refusal(403, 'step-up-required')}>
      <Text>console</Text>
    </AdminOnly>
  );
  expect(screen.queryByTestId('admin-forbidden')).toBeNull();
  expect(screen.getByText('console')).toBeTruthy();
});

it('leaves an ordinary failure to the screen that raised it', async () => {
  await render(
    <AdminOnly error={refusal(500, 'internal-error')}>
      <Text>console</Text>
    </AdminOnly>
  );
  expect(screen.queryByTestId('admin-forbidden')).toBeNull();
  expect(screen.getByText('console')).toBeTruthy();
});
