import { useId } from 'react'
import styles from './QlInput.module.css'

interface QlInputProps extends Omit<
  React.InputHTMLAttributes<HTMLInputElement>,
  'prefix' | 'suffix'
> {
  label?: string
  error?: string
  hint?: string
  prefix?: React.ReactNode
  suffix?: React.ReactNode
}

export function QlInput({ label, error, hint, prefix, suffix, id, ...props }: QlInputProps) {
  const generatedId = useId()
  const inputId = id ?? generatedId
  const hasError = Boolean(error)
  return (
    <div className={styles.wrapper}>
      {label && (
        <label data-testid="ql-input-label" htmlFor={inputId} className={styles.label}>
          {label}
        </label>
      )}
      <div className={`${styles.inputRow} ${hasError ? styles.hasError : ''}`}>
        {prefix && (
          <span data-testid="ql-input-prefix" className={styles.prefix}>
            {prefix}
          </span>
        )}
        <input
          id={inputId}
          data-testid="ql-input"
          className={`${styles.input} ${prefix ? styles.withPrefix : ''}`}
          aria-invalid={hasError}
          aria-describedby={error ? `${inputId}-error` : hint ? `${inputId}-hint` : undefined}
          {...props}
        />
        {suffix && (
          <span data-testid="ql-input-suffix" className={styles.suffix}>
            {suffix}
          </span>
        )}
      </div>
      {error && (
        <span
          id={`${inputId}-error`}
          data-testid="ql-input-error"
          className={styles.error}
          role="alert"
        >
          {error}
        </span>
      )}
      {hint && !error && (
        <span id={`${inputId}-hint`} className={styles.hint}>
          {hint}
        </span>
      )}
    </div>
  )
}
