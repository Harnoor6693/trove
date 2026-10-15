/**
 * Assets — one shared household ledger, CRUD (§4.7, step 203, scope item 3).
 *
 * `currency` is not writable on an edit: changing it would reinterpret `value`
 * (a figure already recorded in a specific currency), so `AssetUpdateRequest`
 * does not carry it and neither does this form.
 */
import { useState } from 'react'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlInput } from '@components/ui/QlInput/QlInput'
import { QlModal } from '@components/ui/QlModal/QlModal'
import { QlSkeleton } from '@components/ui/QlSkeleton/QlSkeleton'
import { QlTable, type QlTableColumn } from '@components/ui/QlTable/QlTable'
import { EmptyState } from '@components/EmptyState/EmptyState'
import { MoneyDisplay } from '@components/MoneyDisplay/MoneyDisplay'
import { useDialogFocus } from '@hooks/useDialogFocus'
import {
  useAssets,
  useCreateAssetMutation,
  useDeleteAssetMutation,
  useUpdateAssetMutation,
} from '@hooks/useNetWorth'
import { netWorthErrorCopy } from './errorCopy'
import { COUNTRY_PROFILES } from '@api/types'
import type { AssetResponse, AssetType } from '@api/types'
import styles from './NetWorth.module.css'

const ASSET_TYPES: AssetType[] = ['CASH', 'INVESTMENT', 'REAL_ESTATE', 'VEHICLE', 'OTHER']
const ASSET_TYPE_LABEL: Record<AssetType, string> = {
  CASH: 'Cash',
  INVESTMENT: 'Investment',
  REAL_ESTATE: 'Real estate',
  VEHICLE: 'Vehicle',
  OTHER: 'Other',
}

interface DialogShellProps {
  title: string
  onClose: () => void
  children: React.ReactNode
  testId: string
}

function DialogShell({ title, onClose, children, testId }: DialogShellProps) {
  const ref = useDialogFocus<HTMLDivElement>(true)
  return (
    <QlModal isOpen onClose={onClose} title={title}>
      <div ref={ref} tabIndex={-1} className={styles.stack} data-testid={testId}>
        {children}
      </div>
    </QlModal>
  )
}

function CreateAssetDialog({ onClose }: { onClose: () => void }) {
  const create = useCreateAssetMutation()
  const [name, setName] = useState('')
  const [assetType, setAssetType] = useState<AssetType>('CASH')
  const [value, setValue] = useState('')
  const [currency, setCurrency] = useState('')

  function submit(e: React.FormEvent) {
    e.preventDefault()
    create.mutate(
      {
        name,
        assetType,
        value,
        currency: currency ? (currency as AssetResponse['currency']) : null,
      },
      { onSuccess: onClose }
    )
  }

  return (
    <DialogShell title="Add an asset" onClose={onClose} testId="create-asset-dialog">
      <form className={styles.stack} onSubmit={submit}>
        <QlInput
          label="Name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={create.error?.fieldError('name')}
          required
        />
        <label className={styles.selectLabel} htmlFor="asset-type">
          Type
        </label>
        <select
          id="asset-type"
          className={styles.select}
          value={assetType}
          onChange={(e) => setAssetType(e.target.value as AssetType)}
        >
          {ASSET_TYPES.map((t) => (
            <option key={t} value={t}>
              {ASSET_TYPE_LABEL[t]}
            </option>
          ))}
        </select>
        <QlInput
          label="Value"
          inputMode="decimal"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          error={create.error?.fieldError('value')}
          required
        />
        <label className={styles.selectLabel} htmlFor="asset-currency">
          Currency
        </label>
        <select
          id="asset-currency"
          className={styles.select}
          value={currency}
          onChange={(e) => setCurrency(e.target.value)}
        >
          <option value="">My reporting currency</option>
          {COUNTRY_PROFILES.map((p) => (
            <option key={p.currency} value={p.currency}>
              {p.currency}
            </option>
          ))}
        </select>
        {create.isError &&
          !create.error.fieldError('name') &&
          !create.error.fieldError('value') && (
            <p className={styles.error} role="alert">
              {netWorthErrorCopy(create.error)}
            </p>
          )}
        <div className={styles.dialogActions}>
          <QlButton type="button" variant="ghost" onClick={onClose}>
            Cancel
          </QlButton>
          <QlButton type="submit" variant="primary" loading={create.isPending}>
            Add asset
          </QlButton>
        </div>
      </form>
    </DialogShell>
  )
}

function EditAssetDialog({ asset, onClose }: { asset: AssetResponse; onClose: () => void }) {
  const update = useUpdateAssetMutation()
  const [name, setName] = useState(asset.name)
  const [assetType, setAssetType] = useState<AssetType>(asset.assetType)
  const [value, setValue] = useState(asset.value)

  function submit(e: React.FormEvent) {
    e.preventDefault()
    update.mutate(
      { id: asset.id, body: { name, assetType, value, accountId: asset.accountId } },
      { onSuccess: onClose }
    )
  }

  return (
    <DialogShell title={`Edit ${asset.name}`} onClose={onClose} testId="edit-asset-dialog">
      <form className={styles.stack} onSubmit={submit}>
        <QlInput
          label="Name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={update.error?.fieldError('name')}
          required
        />
        <label className={styles.selectLabel} htmlFor="edit-asset-type">
          Type
        </label>
        <select
          id="edit-asset-type"
          className={styles.select}
          value={assetType}
          onChange={(e) => setAssetType(e.target.value as AssetType)}
        >
          {ASSET_TYPES.map((t) => (
            <option key={t} value={t}>
              {ASSET_TYPE_LABEL[t]}
            </option>
          ))}
        </select>
        <QlInput
          label="Value"
          inputMode="decimal"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          error={update.error?.fieldError('value')}
          required
        />
        {update.isError && !update.error.fieldError('name') && !update.error.fieldError('value') && (
          <p className={styles.error} role="alert">
            {netWorthErrorCopy(update.error)}
          </p>
        )}
        <div className={styles.dialogActions}>
          <QlButton type="button" variant="ghost" onClick={onClose}>
            Cancel
          </QlButton>
          <QlButton type="submit" variant="primary" loading={update.isPending}>
            Save
          </QlButton>
        </div>
      </form>
    </DialogShell>
  )
}

function DeleteAssetDialog({ asset, onClose }: { asset: AssetResponse; onClose: () => void }) {
  const remove = useDeleteAssetMutation()
  return (
    <DialogShell title={`Remove ${asset.name}?`} onClose={onClose} testId="delete-asset-dialog">
      <p className={styles.lede}>
        This removes the asset from your net-worth ledger. It does not touch any linked account or
        transaction.
      </p>
      {remove.isError && (
        <p className={styles.error} role="alert">
          {netWorthErrorCopy(remove.error)}
        </p>
      )}
      <div className={styles.dialogActions}>
        <QlButton variant="ghost" onClick={onClose}>
          Keep it
        </QlButton>
        <QlButton
          variant="danger"
          loading={remove.isPending}
          onClick={() => remove.mutate(asset.id, { onSuccess: onClose })}
        >
          Remove
        </QlButton>
      </div>
    </DialogShell>
  )
}

type AssetDialog =
  | { kind: 'create' }
  | { kind: 'edit'; asset: AssetResponse }
  | { kind: 'delete'; asset: AssetResponse }
  | null

export function AssetsPanel() {
  const assets = useAssets()
  const [dialog, setDialog] = useState<AssetDialog>(null)
  const rows = assets.data ?? []

  const columns: QlTableColumn<AssetResponse & Record<string, unknown>>[] = [
    { key: 'name', header: 'Name' },
    {
      key: 'assetType',
      header: 'Type',
      render: (row) => ASSET_TYPE_LABEL[row.assetType],
    },
    {
      key: 'value',
      header: 'Value',
      align: 'right',
      render: (row) => <MoneyDisplay amount={row.value} currency={row.currency} colored={false} />,
    },
    {
      key: 'actions',
      header: 'Actions',
      render: (row) => (
        <div className={styles.rowActions}>
          <QlButton size="sm" variant="ghost" onClick={() => setDialog({ kind: 'edit', asset: row })}>
            Edit
          </QlButton>
          <QlButton
            size="sm"
            variant="ghost"
            onClick={() => setDialog({ kind: 'delete', asset: row })}
          >
            Remove
          </QlButton>
        </div>
      ),
    },
  ]

  return (
    <QlCard>
      <div className={styles.header}>
        <h2 className={styles.sectionTitle}>Assets</h2>
        <QlButton size="sm" variant="primary" onClick={() => setDialog({ kind: 'create' })}>
          Add asset
        </QlButton>
      </div>

      {assets.isPending && <QlSkeleton data-testid="assets-skeleton" />}
      {assets.isError && (
        <p className={styles.error} role="alert">
          {netWorthErrorCopy(assets.error)}
        </p>
      )}
      {assets.data && rows.length === 0 && (
        <EmptyState title="No assets yet">
          <p>Add a bank account, an investment or anything else you own.</p>
        </EmptyState>
      )}
      {assets.data && rows.length > 0 && (
        <div className={styles.tableWrap} data-testid="assets-table">
          <QlTable columns={columns} rows={rows as (AssetResponse & Record<string, unknown>)[]} />
        </div>
      )}

      {dialog?.kind === 'create' && <CreateAssetDialog onClose={() => setDialog(null)} />}
      {dialog?.kind === 'edit' && (
        <EditAssetDialog asset={dialog.asset} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'delete' && (
        <DeleteAssetDialog asset={dialog.asset} onClose={() => setDialog(null)} />
      )}
    </QlCard>
  )
}
