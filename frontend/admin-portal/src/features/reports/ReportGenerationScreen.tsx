import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Grid,
  Link,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { useReportTypes, useRequestReport } from './hooks';
import type { ReportFormat } from './api';

/** Number of days between two ISO dates, inclusive-ish (for the async note). */
function rangeDays(from: string, to: string): number {
  const a = new Date(from).getTime();
  const b = new Date(to).getTime();
  if (!Number.isFinite(a) || !Number.isFinite(b)) return 0;
  return Math.round((b - a) / (1000 * 60 * 60 * 24));
}

/**
 * Report Generation module (Requirement 19.2, Requirement 20). Lets an admin
 * pick a pre-built report, date range, and export format. Ranges of 7 days or
 * fewer return a download link inline; longer ranges are generated
 * asynchronously and delivered by email (Requirement 20.2/20.3).
 */
export function ReportGenerationScreen() {
  const typesQuery = useReportTypes();
  const request = useRequestReport();

  const [reportTypeId, setReportTypeId] = useState('');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [format, setFormat] = useState<ReportFormat>('PDF');
  const [touched, setTouched] = useState(false);

  const days = rangeDays(fromDate, toDate);
  const willBeAsync = days > 7;
  const rangeInvalid = !fromDate || !toDate || days < 0;
  const formInvalid = !reportTypeId || rangeInvalid;

  const handleGenerate = () => {
    setTouched(true);
    if (formInvalid) return;
    request.mutate({ reportTypeId, fromDate, toDate, format });
  };

  return (
    <ModuleScreen
      title="Report Generation"
      description="Generate revenue, performance, and operational reports."
    >
      <QueryStateView
        isLoading={typesQuery.isLoading}
        isError={typesQuery.isError}
        error={typesQuery.error}
        onRetry={() => void typesQuery.refetch()}
      >
        <Card variant="outlined" sx={{ maxWidth: 720 }}>
          <CardContent>
            <Grid container spacing={2}>
              <Grid item xs={12}>
                <TextField
                  select
                  label="Report type"
                  value={reportTypeId}
                  onChange={(event) => setReportTypeId(event.target.value)}
                  fullWidth
                  error={touched && !reportTypeId}
                  helperText={touched && !reportTypeId ? 'Select a report type.' : undefined}
                >
                  {(typesQuery.data ?? []).map((type) => (
                    <MenuItem key={type.id} value={type.id}>
                      <Stack direction="row" spacing={1} alignItems="center">
                        <span>{type.name}</span>
                        {type.financeOnly ? (
                          <Chip size="small" label="Finance" color="secondary" />
                        ) : null}
                      </Stack>
                    </MenuItem>
                  ))}
                </TextField>
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField
                  label="From"
                  type="date"
                  value={fromDate}
                  onChange={(event) => setFromDate(event.target.value)}
                  InputLabelProps={{ shrink: true }}
                  fullWidth
                  error={touched && !fromDate}
                />
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField
                  label="To"
                  type="date"
                  value={toDate}
                  onChange={(event) => setToDate(event.target.value)}
                  InputLabelProps={{ shrink: true }}
                  fullWidth
                  error={touched && (!toDate || days < 0)}
                  helperText={touched && days < 0 ? 'To date must be after from date.' : undefined}
                />
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField
                  select
                  label="Format"
                  value={format}
                  onChange={(event) => setFormat(event.target.value as ReportFormat)}
                  fullWidth
                >
                  <MenuItem value="PDF">PDF</MenuItem>
                  <MenuItem value="CSV">CSV</MenuItem>
                </TextField>
              </Grid>
            </Grid>

            {willBeAsync ? (
              <Alert severity="info" sx={{ mt: 2 }}>
                Ranges longer than 7 days are generated in the background and emailed to you when
                ready.
              </Alert>
            ) : null}

            <Box sx={{ mt: 2 }}>
              <Button variant="contained" onClick={handleGenerate} disabled={request.isPending}>
                {request.isPending ? 'Generating…' : 'Generate report'}
              </Button>
            </Box>

            {request.isError ? (
              <Alert severity="error" sx={{ mt: 2 }}>
                {request.error.message}
              </Alert>
            ) : null}

            {request.isSuccess ? (
              <Alert severity="success" sx={{ mt: 2 }}>
                <Typography variant="body2">{request.data.message}</Typography>
                {request.data.mode === 'SYNC' && request.data.downloadUrl ? (
                  <Link href={request.data.downloadUrl} target="_blank" rel="noopener">
                    Download report
                  </Link>
                ) : null}
              </Alert>
            ) : null}
          </CardContent>
        </Card>
      </QueryStateView>
    </ModuleScreen>
  );
}
